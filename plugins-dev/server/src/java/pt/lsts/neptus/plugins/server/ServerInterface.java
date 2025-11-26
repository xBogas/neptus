package pt.lsts.neptus.plugins.server;

import com.google.common.eventbus.Subscribe;
import pt.lsts.imc.EntityList;
import pt.lsts.imc.IMCDefinition;
import pt.lsts.imc.IMCMessage;
import pt.lsts.imc.StateReport;
import pt.lsts.imc.VerticalProfile;
import pt.lsts.neptus.NeptusLog;
import pt.lsts.neptus.console.ConsoleLayout;
import pt.lsts.neptus.console.ConsolePanel;
import pt.lsts.neptus.plugins.PluginDescription;
import pt.lsts.neptus.plugins.Popup;

import javax.swing.*;
import java.awt.*;

/**
 * @author João Bogas
 */
@PluginDescription(name = "Server Interface")
@Popup(pos = Popup.POSITION.CENTER, width = 250, height = 250, accelerator = 'Y')
public class ServerInterface extends ConsolePanel {

    private final ImcTcpClient client = new ImcTcpClient(IMCDefinition.getInstance());
    private JTextArea statusLabel;
    private JTextField ipField;
    private JTextField portField;

    public ServerInterface(ConsoleLayout console) {
        super(console);
    }

    @Subscribe
    public void onStateReport(StateReport msg) {
        NeptusLog.pub().info("Reading StateReport: {}", msg.getSourceName());

        sendMessage(msg);
    }

    @Subscribe
    public void onVerticalProfile(VerticalProfile profile) {
        NeptusLog.pub().info("Reading VerticalProfile: {}", profile);
    }

    @Override
    public void initSubPanel() {
        removeAll();
        showConnectionForm();
        revalidate();
        repaint();

        getConsole().getImcMsgManager().addListener(this);
    }

    /**
     * UI Connection Form
     */
    private void showConnectionForm() {
        removeAll();
        setLayout(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.anchor = GridBagConstraints.NORTHWEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        gbc.weighty = 0.0;


        gbc.gridx = 0;
        gbc.gridy = 0;
        add(new JLabel("Server IP:"), gbc);
        gbc.gridx = 1;
        ipField = new JTextField("127.0.0.1", 10);
        add(ipField, gbc);

        gbc.gridx = 0;
        gbc.gridy++;
        add(new JLabel("Port:"), gbc);
        gbc.gridx = 1;
        portField = new JTextField("6005", 5);
        add(portField, gbc);

        gbc.gridx = 0;
        gbc.gridy++;
        gbc.gridwidth = 2;
        statusLabel = new JTextArea("Not connected");
        statusLabel.setEditable(false);
        statusLabel.setLineWrap(true);
        statusLabel.setWrapStyleWord(true);
        statusLabel.setBackground(getBackground());
//        statusLabel.setFont(getFont());
        gbc.fill = GridBagConstraints.BOTH;
        gbc.weightx = 1.0;
        add(statusLabel, gbc);

        gbc.gridy++;

        JButton connectButton = new JButton("Connect");
        connectButton.addActionListener(e -> {
            String ip = ipField.getText().trim();
            int port;
            try {
                port = Integer.parseInt(portField.getText().trim());
            }
            catch (NumberFormatException ex) {
                statusLabel.setText("Invalid port!");
                return;
            }
            connectToServer(ip, port);
        });
        add(connectButton, gbc);

        revalidate();
        repaint();
    }

    /**
     * UI Valid Connection Form
     */
    private void showConnectedPanel(String ip, int port) {
        removeAll();
        setLayout(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();

        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.gridx = 0;
        gbc.gridy = 0;

        statusLabel.setText(("Connected to " + ip + ":" + port));
        add(statusLabel, gbc);


        gbc.gridy = 1;
        gbc.gridx = 0;
        JButton getInstructionsButton = new JButton("Get Instructions");
        getInstructionsButton.addActionListener(e -> {
            StateReport stateReport = new StateReport();
            sendMessage(stateReport);
        });
        add(getInstructionsButton, gbc);

        gbc.gridy = 2;
        gbc.gridx = 0;

        JButton stopButton = new JButton("Stop");
        stopButton.addActionListener(e -> {
            NeptusLog.pub().debug("Request SOI to stop");
            cleanSubPanel();
        });
        add(stopButton, gbc);

        revalidate();
        repaint();
    }

    private void connectToServer(String serverIp, int serverPort) {
        try {
            client.addListener(new ImcTcpClient.MessageListener() {
                @Override
                public void onMessage(IMCMessage msg, String remote) {
                    handleIncoming(msg, remote);
                }

                @Override
                public void onDisconnect(String remote, Exception e) {
                    serverDisconnected(remote, e);
                }
            });

            client.connect(serverIp, serverPort, 100);

            SwingUtilities.invokeLater(() -> showConnectedPanel(serverIp, serverPort));
        }
        catch (Exception e) {
            SwingUtilities.invokeLater(() -> statusLabel.setText("Connection failed: " + e.getMessage()));
            NeptusLog.pub().error("Could not connect to {}:{}", serverIp, serverPort);
        }
    }

    private void sendMessage(IMCMessage msg) {
        try {
            NeptusLog.pub().info("Sending message: {}", msg);

            client.send(msg);
        }
        catch (Exception e) {
            NeptusLog.pub().warn("Failed to send message: {}", e.getMessage());
        }
    }

    private void handleIncoming(IMCMessage msg, String remote) {
        NeptusLog.pub().info("Received message: {} from {}", msg.getAbbrev(), remote);
    }

    public void serverDisconnected(String remote, Exception cause) {
        NeptusLog.pub().debug("Disconnected from {}: {}", remote, cause.getMessage());

        SwingUtilities.invokeLater(this::showConnectionForm);
    }

    @Override
    public void cleanSubPanel() {
        NeptusLog.pub().warn("Closing connection form");
        client.close();
    }
}

