import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.net.*;

public class ChatClient {
  private Socket socket;
  private InputStream inStream;
  private OutputStream outStream;
  private JFrame frame;
  private JTextArea chatArea;
  private JTextField messageField;
  private JButton sendButton, sendFileButton, createGroupButton, addMemberButton, exitButton;
  private JList<String> userList, groupList;
  private DefaultListModel<String> userListModel, groupListModel;
  private String username;
  private String selectedTarget;

  public ChatClient() {
    connectToServer();
    buildGUI();
    new Thread(this::listenFromServer).start();
  }

  private void connectToServer() {
    while (true) {
      try {
        socket = new Socket("localhost", 8080);
        inStream = socket.getInputStream();
        outStream = socket.getOutputStream();
        String prompt = readLine(inStream);
        if (!"NOME?".equals(prompt)) {
          JOptionPane.showMessageDialog(null, "Protocolo inesperado do servidor.", "Erro", JOptionPane.ERROR_MESSAGE);
          socket.close();
          System.exit(1);
        }
        username = askUsername();
        writeLine(outStream, username);
        String response = readLine(inStream);
        if (response == null) {
          JOptionPane.showMessageDialog(null, "Conexão encerrada pelo servidor.", "Erro", JOptionPane.ERROR_MESSAGE);
          socket.close();
          System.exit(1);
        }
        if (!response.startsWith("ERRO")) {
          break;
        } else {
          JOptionPane.showMessageDialog(null, "Nome de usuário inválido ou já em uso.", "Erro",
              JOptionPane.ERROR_MESSAGE);
        }
      } catch (IOException e) {
        JOptionPane.showMessageDialog(null, "Não foi possível conectar ao servidor.", "Erro",
            JOptionPane.ERROR_MESSAGE);
        System.exit(1);
      }
    }
  }

  private String askUsername() {
    return JOptionPane.showInputDialog(null, "Digite seu nome de usuário:", "Login", JOptionPane.PLAIN_MESSAGE);
  }

  private void buildGUI() {
    frame = new JFrame("Chat - Usuário: " + username);
    frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
    frame.setSize(700, 500);
    frame.setLayout(new BorderLayout());

    // Chat area
    chatArea = new JTextArea();
    chatArea.setEditable(false);
    JScrollPane chatScroll = new JScrollPane(chatArea);
    frame.add(chatScroll, BorderLayout.CENTER);

    // Lateral lists
    JPanel sidePanel = new JPanel(new GridLayout(2, 1));
    userListModel = new DefaultListModel<>();
    userList = new JList<>(userListModel);
    userList.setBorder(BorderFactory.createTitledBorder("Usuários"));
    groupListModel = new DefaultListModel<>();
    groupList = new JList<>(groupListModel);
    groupList.setBorder(BorderFactory.createTitledBorder("Grupos"));
    sidePanel.add(new JScrollPane(userList));
    sidePanel.add(new JScrollPane(groupList));
    sidePanel.setPreferredSize(new Dimension(180, 0));
    frame.add(sidePanel, BorderLayout.WEST);

    // Bottom panel
    JPanel bottomPanel = new JPanel(new BorderLayout());
    messageField = new JTextField();
    bottomPanel.add(messageField, BorderLayout.CENTER);
    JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
    sendButton = new JButton("Enviar mensagem");
    sendFileButton = new JButton("Enviar arquivo");
    createGroupButton = new JButton("Criar grupo");
    addMemberButton = new JButton("Adicionar membro");
    exitButton = new JButton("Sair");
    exitButton.setForeground(Color.RED);
    buttonPanel.add(sendButton);
    buttonPanel.add(sendFileButton);
    buttonPanel.add(createGroupButton);
    buttonPanel.add(addMemberButton);
    buttonPanel.add(Box.createHorizontalStrut(30));
    buttonPanel.add(exitButton);
    bottomPanel.add(buttonPanel, BorderLayout.SOUTH);
    frame.add(bottomPanel, BorderLayout.SOUTH);

    // List selection logic
    ListSelectionModel userSel = userList.getSelectionModel();
    ListSelectionModel groupSel = groupList.getSelectionModel();
    userSel.addListSelectionListener(e -> {
      if (!e.getValueIsAdjusting()) {
        groupList.clearSelection();
        selectedTarget = userList.getSelectedValue();
      }
    });
    groupSel.addListSelectionListener(e -> {
      if (!e.getValueIsAdjusting()) {
        userList.clearSelection();
        selectedTarget = groupList.getSelectedValue();
      }
    });

    // Button actions
    sendButton.addActionListener(e -> sendMessage());
    sendFileButton.addActionListener(e -> sendFile());
    createGroupButton.addActionListener(e -> createGroup());
    addMemberButton.addActionListener(e -> addMember());
    exitButton.addActionListener(e -> exitChat());
    messageField.addActionListener(e -> sendMessage());

    frame.setVisible(true);
  }

  private void sendMessage() {
    String msg = messageField.getText().trim();
    if (msg.isEmpty() || selectedTarget == null)
      return;
    writeLine(outStream, "/msg " + selectedTarget + " " + msg);
    chatArea.append("(Você → " + selectedTarget + "): " + msg + "\n");
    System.out.println("Enviado para " + selectedTarget + ": " + msg);
    messageField.setText("");
  }

  private void sendFile() {
    if (selectedTarget == null) {
      System.out.println("Nenhum alvo selecionado para envio de arquivo.");
      return;
    }
    JFileChooser fileChooser = new JFileChooser();
    int result = fileChooser.showOpenDialog(frame);
    if (result == JFileChooser.APPROVE_OPTION) {
      File file = fileChooser.getSelectedFile();
      try {
        String comando = "/arquivo " + selectedTarget + " " + file.getName() + " " + file.length();
        System.out.println("Enviando comando para o servidor: " + comando);
        writeLine(outStream, comando);
        FileInputStream fis = new FileInputStream(file);
        byte[] buffer = new byte[4096];
        int count;
        long total = 0;
        while ((count = fis.read(buffer)) > 0) {
          outStream.write(buffer, 0, count);
          total += count;
        }
        outStream.flush();
        fis.close();
        System.out.println("Total de bytes enviados: " + total + "/" + file.length());
        String msg = "Arquivo enviado para " + selectedTarget + ": " + file.getName();
        chatArea.append(msg + "\n");
        System.out.println(msg);
      } catch (IOException ex) {
        ex.printStackTrace();
        JOptionPane.showMessageDialog(frame, "Erro ao enviar arquivo.", "Erro", JOptionPane.ERROR_MESSAGE);
      }
    }
  }

  private void createGroup() {
    String groupName = JOptionPane.showInputDialog(frame, "Nome do grupo:");
    if (groupName != null && !groupName.trim().isEmpty()) {
      writeLine(outStream, "/grupo_criar " + groupName.trim());
    }
  }

  private void addMember() {
    if (selectedTarget == null || groupList.getSelectedValue() == null)
      return;
    String member = JOptionPane.showInputDialog(frame, "Nome do usuário para adicionar ao grupo:");
    if (member != null && !member.trim().isEmpty()) {
      writeLine(outStream, "/grupo_add " + groupList.getSelectedValue() + " " + member.trim());
    }
  }

  private void exitChat() {
    writeLine(outStream, "close");
    try {
      socket.close();
    } catch (IOException e) {
    }
    frame.dispose();
    System.exit(0);
  }

  private void listenFromServer() {
    try {
      String line;
      while ((line = readLine(inStream)) != null) {
        System.out.println("[DEBUG] Linha recebida do servidor: " + line);
        if (line.startsWith("USERLIST|")) {
          updateUserList(line.substring(9));
        } else if (line.startsWith("GROUPLIST|")) {
          updateGroupList(line.substring(10));
        } else if (line.startsWith("GRUPO:")) {
          String[] partsGrupo = line.split(":", 3);
          if (partsGrupo.length == 3) {
            String groupName = partsGrupo[1];
            String[] members = partsGrupo[2].split(",");
            for (String member : members) {
              if (member.equals(username)) {
                SwingUtilities.invokeLater(() -> {
                  if (groupListModel.indexOf(groupName) == -1) {
                    groupListModel.addElement(groupName);
                  }
                });
                break;
              }
            }
          }
        } else if (line.startsWith("MSG:")) {
          String[] partsMsg = line.split(":", 3);
          if (partsMsg.length == 3) {
            chatArea.append(partsMsg[1] + ": " + partsMsg[2] + "\n");
          }
        } else if (line.startsWith("GRUPO_MSG:")) {
          String[] partsGrupoMsg = line.split(":", 4);
          if (partsGrupoMsg.length == 4) {
            chatArea.append(partsGrupoMsg[1] + " - " + partsGrupoMsg[2] + ": " + partsGrupoMsg[3] + "\n");
          }
        } else if (line.startsWith("ARQUIVO:")) {
          String[] partsArq = line.split(":", 4);
          if (partsArq.length == 4) {
            receiveFile(partsArq[1], null, partsArq[2], Long.parseLong(partsArq[3]));
          }
        } else if (line.startsWith("GRUPO_ARQUIVO:")) {
          String[] partsGrupoArq = line.split(":", 5);
          if (partsGrupoArq.length == 5) {
            receiveFile(partsGrupoArq[2], partsGrupoArq[1], partsGrupoArq[3], Long.parseLong(partsGrupoArq[4]));
          }
        }
      }
    } catch (IOException e) {
      chatArea.append("Conexão encerrada.\n");
    }
  }

  private void writeLine(OutputStream os, String line) {
    try {
      os.write((line + "\n").getBytes("UTF-8"));
      os.flush();
    } catch (IOException e) {
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

  private void updateUserList(String users) {
    SwingUtilities.invokeLater(() -> {
      userListModel.clear();
      for (String user : users.split(",")) {
        if (!user.equals(username) && !user.isEmpty())
          userListModel.addElement(user);
      }
    });
  }

  private void updateGroupList(String groups) {
    SwingUtilities.invokeLater(() -> {
      groupListModel.clear();
      for (String group : groups.split(",")) {
        if (!group.isEmpty())
          groupListModel.addElement(group);
      }
    });
  }

  private void receiveFile(String from, String group, String filename, long size) throws IOException {
    System.out
        .println("[DEBUG] Iniciando recebimento de arquivo: " + filename + " de " + from + " (" + size + " bytes)");
    String msg = (group == null) ? "Arquivo recebido de " + from + ": " + filename
        : "Arquivo recebido do grupo " + group + " - " + from + ": " + filename;
    chatArea.append(msg + "\n");

    File file = new File(filename);
    FileOutputStream fos = new FileOutputStream(file);
    byte[] buffer = new byte[4096];
    long remaining = size;
    long lidos = 0;
    while (remaining > 0) {
      int read = inStream.read(buffer, 0, (int) Math.min(buffer.length, remaining));
      if (read == -1)
        break;
      fos.write(buffer, 0, read);
      remaining -= read;
      lidos += read;
    }
    fos.close();
    System.out.println("[DEBUG] Arquivo salvo automaticamente: " + file.getAbsolutePath());
    System.out.println("[DEBUG] Fim do recebimento de arquivo: " + filename + ", bytes lidos: " + lidos + "/" + size);
  }

  public static void main(String[] args) {
    SwingUtilities.invokeLater(ChatClient::new);
  }
}
