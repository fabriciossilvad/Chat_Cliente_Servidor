import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

public class ChatServer {
    private static final int PORT = 8080;
    private static final Pattern VALID_NAME = Pattern.compile("^[A-Za-z0-9_]{3,}$");
    private static final Map<String, ClientHandler> usuariosConectados = new ConcurrentHashMap<>();
    private static final Map<String, Set<String>> grupos = new ConcurrentHashMap<>();
    private static final Object logLock = new Object();

    public static void main(String[] args) throws IOException {
        ServerSocket serverSocket = new ServerSocket(PORT);
        log("Servidor iniciado na porta " + PORT);

        try {
            while (true) {
                Socket socket = serverSocket.accept();
                new Thread(new ClientHandler(socket)).start();
            }
        } finally {
            if (serverSocket != null && !serverSocket.isClosed()) {
                try {
                    serverSocket.close();
                    System.out.println("ServerSocket fechado com sucesso.");
                } catch (IOException e) {
                    System.out.println("Erro ao fechar o ServerSocket: " + e.getMessage());
                }
            }
        }
    }

    private static boolean nomeValido(String nome) {
        return VALID_NAME.matcher(nome).matches();
    }

    public static void log(String msg) {
        synchronized (logLock) {
            System.out.println("[" + new Date() + "] " + msg);
        }
    }

    private static void atualizarUsuarios() {
        String lista = "USERLIST|" + String.join(",", usuariosConectados.keySet());
        for (ClientHandler ch : usuariosConectados.values()) {
            ch.enviar(lista);
        }
    }

    private static void atualizarGrupos(String grupo) {
        Set<String> membros = grupos.get(grupo);
        if (membros != null) {
            String lista = "GRUPO:" + grupo + ":" + String.join(",", membros);
            for (String membro : membros) {
                ClientHandler ch = usuariosConectados.get(membro);
                if (ch != null)
                    ch.enviar(lista);
            }
        }
    }

    static class ClientHandler implements Runnable {
        private Socket socket;
        private String usuario;
        private InputStream inStream;
        private OutputStream outStream;
        private PrintWriter out;

        public ClientHandler(Socket socket) {
            this.socket = socket;
        }

        public void enviar(String msg) {
            out.println(msg);
            out.flush();
        }

        public void run() {
            try {
                inStream = socket.getInputStream();
                outStream = socket.getOutputStream();
                out = new PrintWriter(outStream, true);

                // Solicita nome do usuário
                out.println("NOME?");
                String nome = readLine(inStream);
                if (nome == null || nome.trim().isEmpty() || nome.equalsIgnoreCase("null") || !nomeValido(nome)
                        || usuariosConectados.containsKey(nome)) {
                    log("Tentativa de conexão falha: " + nome);
                    out.println("ERRO:Nome inválido ou já em uso.");
                    socket.close();
                    return;
                }
                usuario = nome;
                usuariosConectados.put(usuario, this);
                log("Usuário conectado: " + usuario);
                // Envia a lista de usuários apenas para o novo usuário
                enviar("USERLIST|" + String.join(",", usuariosConectados.keySet()));
                // Atualiza a lista para todos os outros
                atualizarUsuarios();

                String linha;
                while ((linha = readLine(inStream)) != null) {
                    if (linha.equalsIgnoreCase("close"))
                        break;
                    processarMensagem(linha);
                }

            } catch (IOException e) {
                // ignorar
            } finally {
                if (usuario != null) {
                    usuariosConectados.remove(usuario);
                    log("Usuário desconectado: " + usuario);
                    atualizarUsuarios();
                }
                try {
                    socket.close();
                } catch (IOException e) {
                }
            }
        }

        private void processarMensagem(String linha) {
            try {
                if (linha.startsWith("/grupo_criar ")) {
                    String nomeGrupo = linha.substring(13).trim();
                    if (!nomeValido(nomeGrupo) || grupos.containsKey(nomeGrupo)) {
                        out.println("ERRO:Nome de grupo inválido ou já existe.");
                        return;
                    }
                    grupos.put(nomeGrupo, ConcurrentHashMap.newKeySet());
                    grupos.get(nomeGrupo).add(usuario);
                    log("Grupo criado: " + nomeGrupo + " por " + usuario);
                    atualizarGrupos(nomeGrupo);

                } else if (linha.startsWith("/grupo_add ")) {
                    String[] partes = linha.substring(11).split(" ");
                    if (partes.length != 2) {
                        out.println("ERRO:Uso: /grupo_add grupo usuario");
                        return;
                    }
                    String grupo = partes[0], membro = partes[1];
                    if (!grupos.containsKey(grupo) || !usuariosConectados.containsKey(membro)) {
                        out.println("ERRO:Grupo ou usuário não existe.");
                        return;
                    }
                    grupos.get(grupo).add(membro);
                    log("Membro adicionado: " + membro + " ao grupo " + grupo + " por " + usuario);
                    atualizarGrupos(grupo);

                } else if (linha.startsWith("/msg ")) {
                    String[] partes = linha.substring(5).split(" ", 2);
                    if (partes.length != 2) {
                        out.println("ERRO:Uso: /msg destino mensagem");
                        return;
                    }
                    String destino = partes[0], mensagem = partes[1];
                    if (usuariosConectados.containsKey(destino)) {
                        usuariosConectados.get(destino).enviar("MSG:" + usuario + ":" + mensagem);
                        log("Mensagem: " + usuario + " → " + destino + ": " + mensagem);
                    } else if (grupos.containsKey(destino) && grupos.get(destino).contains(usuario)) {
                        for (String membro : grupos.get(destino)) {
                            if (!membro.equals(usuario)) {
                                ClientHandler ch = usuariosConectados.get(membro);
                                if (ch != null)
                                    ch.enviar("GRUPO_MSG:" + destino + ":" + usuario + ":" + mensagem);
                            }
                        }
                        log("Mensagem grupo: " + usuario + " → " + destino + ": " + mensagem);
                    } else {
                        out.println("ERRO:Destino não encontrado ou sem permissão.");
                    }

                } else if (linha.startsWith("/arquivo ")) {
                    log("Recebido comando /arquivo: " + linha);

                    String[] partes = linha.split(" ");
                    if (partes.length < 4) {
                        out.println("ERRO:Uso: /arquivo destino nomeArquivo tamanho");
                        return;
                    }

                    String destino = partes[1];
                    long tamanho = Long.parseLong(partes[partes.length - 1]);
                    StringBuilder sb = new StringBuilder();
                    for (int i = 2; i < partes.length - 1; i++) {
                        if (i > 2)
                            sb.append(" ");
                        sb.append(partes[i]);
                    }
                    String nomeArquivo = sb.toString();

                    byte[] buffer = new byte[4096];
                    long total = 0;

                    if (usuariosConectados.containsKey(destino)) {
                        ClientHandler ch = usuariosConectados.get(destino);
                        OutputStream destinoOut = ch.socket.getOutputStream();
                        ch.enviar("ARQUIVO:" + usuario + ":" + nomeArquivo + ":" + tamanho);

                        while (total < tamanho) {
                            int lidos = inStream.read(buffer, 0, (int) Math.min(buffer.length, tamanho - total));
                            if (lidos == -1)
                                return;
                            destinoOut.write(buffer, 0, lidos);
                            total += lidos;
                        }
                        destinoOut.flush();
                        log("[OK] Arquivo enviado para " + destino + " (" + total + " bytes)");

                    } else if (grupos.containsKey(destino) && grupos.get(destino).contains(usuario)) {
                        ByteArrayOutputStream temp = new ByteArrayOutputStream();
                        while (total < tamanho) {
                            int lidos = inStream.read(buffer, 0, (int) Math.min(buffer.length, tamanho - total));
                            if (lidos == -1)
                                return;
                            temp.write(buffer, 0, lidos);
                            total += lidos;
                        }
                        byte[] dados = temp.toByteArray();

                        for (String membro : grupos.get(destino)) {
                            if (!membro.equals(usuario)) {
                                ClientHandler ch = usuariosConectados.get(membro);
                                if (ch != null) {
                                    OutputStream destinoOut = ch.socket.getOutputStream();
                                    ch.enviar("GRUPO_ARQUIVO:" + destino + ":" + usuario + ":" + nomeArquivo + ":"
                                            + dados.length);
                                    destinoOut.write(dados);
                                    destinoOut.flush();
                                    log("[OK] Arquivo entregue a " + membro);
                                }
                            }
                        }
                        log("[OK] Arquivo grupo: " + usuario + " → " + destino + ": " + nomeArquivo);

                    } else {
                        while (total < tamanho) {
                            int lidos = inStream.read(buffer, 0, (int) Math.min(buffer.length, tamanho - total));
                            if (lidos == -1)
                                break;
                            total += lidos;
                        }
                        out.println("ERRO:Destino não encontrado ou sem permissão.");
                        log("ERRO: Destino não encontrado ou sem permissão para arquivo");
                    }

                } else {
                    out.println("ERRO:Comando desconhecido.");
                }
            } catch (Exception e) {
                out.println("ERRO:Falha ao processar comando.");
                e.printStackTrace();
            }
        }

        private String readLine(InputStream is) throws IOException {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            int b;
            while ((b = is.read()) != -1) {
                if (b == '\n')
                    break;
                if (b != '\r')
                    baos.write(b);
            }
            if (baos.size() == 0 && b == -1)
                return null;
            return baos.toString("UTF-8");
        }
    }
}
