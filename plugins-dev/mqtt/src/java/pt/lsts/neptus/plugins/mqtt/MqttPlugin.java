package pt.lsts.neptus.plugins.mqtt;

import com.google.common.eventbus.Subscribe;
import pt.lsts.imc.IMCMessage;
import pt.lsts.imc.LogBookEntry;
import pt.lsts.imc.VerticalProfile;
import pt.lsts.neptus.NeptusLog;
import pt.lsts.neptus.console.ConsoleLayout;
import pt.lsts.neptus.console.ConsolePanel;
import pt.lsts.neptus.console.notifications.Notification;
import pt.lsts.neptus.plugins.ConfigurationListener;
import pt.lsts.neptus.plugins.NeptusProperty;
import pt.lsts.neptus.plugins.PluginDescription;
import pt.lsts.neptus.plugins.Popup;

import javax.swing.*;
import java.awt.*;

/**
 * Console panel that bridges Neptus IMC telemetry with an MQTT broker.
 *
 * Outbound: publishes EstimatedState messages as JSON to
 *   {@code <topicPrefix>/<vehicleName>/state}.
 * Inbound: subscribes to {@code <topicPrefix>/in/#} and logs received payloads
 *   as console notifications.
 *
 * @author João Bogas
 */
@PluginDescription(name = "MQTT Bridge", description = "Publishes vehicle telemetry to an MQTT broker and receives inbound messages.")
@Popup(pos = Popup.POSITION.CENTER, width = 300, height = 280, accelerator = 'M')
public class MqttPlugin extends ConsolePanel implements ConfigurationListener {

    @NeptusProperty(name = "Broker Host", userLevel = NeptusProperty.LEVEL.REGULAR, description = "Hostname or IP of the MQTT broker.")
    private String brokerHost = "127.0.0.1";

    @NeptusProperty(name = "Broker Port", userLevel = NeptusProperty.LEVEL.REGULAR, description = "TCP port of the MQTT broker.")
    private int brokerPort = 1883;

    @NeptusProperty(name = "Client ID", userLevel = NeptusProperty.LEVEL.REGULAR, description = "Unique MQTT client identifier.")
    private String clientId = "neptus-client";

    @NeptusProperty(name = "Topic Prefix", userLevel = NeptusProperty.LEVEL.REGULAR, description = "Base topic prefix for all published and subscribed topics.")
    private String topicPrefix = "neptus";

    @NeptusProperty(name = "QoS", userLevel = NeptusProperty.LEVEL.REGULAR, description = "MQTT Quality of Service level (0, 1, or 2).")
    private int qos = 1;

    @NeptusProperty(name = "Keep-Alive (s)", userLevel = NeptusProperty.LEVEL.REGULAR, description = "MQTT keep-alive interval in seconds.")
    private int keepAlive = 60;

    private final MqttBrokerClient mqttClient = new MqttBrokerClient();

    private JTextArea statusArea;
    private JTextField hostField;
    private JTextField portField;

    public MqttPlugin(ConsoleLayout console) {
        super(console);
    }

    @Override
    public void propertiesChanged() {
        // property edits only take effect after reconnection
    }

    @Override
    public void initSubPanel() {
        showConnectionForm();
    }

    @Override
    public void cleanSubPanel() {
        mqttClient.close();
    }

    // -------------------------------------------------------------------------
    // IMC → MQTT
    // -------------------------------------------------------------------------

    @Subscribe
    public void onLogBookEntry(LogBookEntry msg) {
        publishMessage(msg);
    }

    @Subscribe
    public void onVerticalProfile(VerticalProfile msg) {
        publishMessage(msg);
    }

    private void publishMessage(IMCMessage msg) {
        if (!mqttClient.isConnected()) {
            NeptusLog.pub().debug("Not connected");
            return;
        }

        String vehicle = resolveVehicleName(msg.getSrc());
        String topic = topicPrefix + "/" + vehicle + "/" + msg.getAbbrev();
        NeptusLog.pub().debug("Sending {}", msg.toString());
        mqttClient.publish(topic, msg.asJSON(), qos);
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private String resolveVehicleName(int srcId) {
        try {
            pt.lsts.neptus.comm.manager.imc.ImcSystem sys =
                    pt.lsts.neptus.comm.manager.imc.ImcSystemsHolder.lookupSystem(srcId);
            if (sys != null)
                return sys.getName();
        }
        catch (Exception ignored) {
        }
        return "unknown-" + srcId;
    }

    private static String buildLogEntryJson(LogBookEntry msg, String vehicle) {
        String text = msg.getText().replace("\"", "\\\"").replace("\n", "\\n");
        return String.format(
                "{\"vehicle\":\"%s\",\"type\":\"%s\",\"text\":\"%s\",\"context\":\"%s\",\"timestamp\":%d}",
                vehicle, msg.getType(), text, msg.getContext(), msg.getTimestampMillis());
    }

    private void connectToBroker(String host, int port) {
        mqttClient.addListener(new MqttBrokerClient.MessageListener() {
            @Override
            public void onMessage(String topic, String payload) {
                handleInbound(topic, payload);
            }

            @Override
            public void onDisconnect(String broker, Throwable cause) {
                brokerDisconnected(cause);
            }
        });

        try {
            mqttClient.connect(host, port, clientId, keepAlive);
            mqttClient.subscribe(topicPrefix + "/in/#", qos);

            brokerHost = host;
            brokerPort = port;

            SwingUtilities.invokeLater(() -> showConnectedPanel(host, port));
        }
        catch (Exception e) {
            SwingUtilities.invokeLater(() -> statusArea.setText("Connection failed: " + e.getMessage()));
            NeptusLog.pub().error("Failed to connect to MQTT broker {}:{} — {}", host, port, e.getMessage());
        }
    }

    private void handleInbound(String topic, String payload) {
        NeptusLog.pub().info("MQTT [{}]: {}", topic, payload);
        getConsole().post(Notification.info("MQTT", "[" + topic + "] " + payload));
    }

    private void brokerDisconnected(Throwable cause) {
        NeptusLog.pub().warn("MQTT broker disconnected: {}", cause != null ? cause.getMessage() : "unknown");
        SwingUtilities.invokeLater(this::showConnectionForm);
    }

    // -------------------------------------------------------------------------
    // UI
    // -------------------------------------------------------------------------

    private void showConnectionForm() {
        removeAll();
        setLayout(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;

        gbc.gridx = 0; gbc.gridy = 0;
        add(new JLabel("Broker Host:"), gbc);
        gbc.gridx = 1;
        hostField = new JTextField(brokerHost, 14);
        add(hostField, gbc);

        gbc.gridx = 0; gbc.gridy++;
        add(new JLabel("Port:"), gbc);
        gbc.gridx = 1;
        portField = new JTextField(String.valueOf(brokerPort), 6);
        add(portField, gbc);

        gbc.gridx = 0; gbc.gridy++;
        gbc.gridwidth = 2;
        statusArea = new JTextArea("Not connected");
        statusArea.setEditable(false);
        statusArea.setLineWrap(true);
        statusArea.setWrapStyleWord(true);
        statusArea.setBackground(getBackground());
        gbc.fill = GridBagConstraints.BOTH;
        gbc.weighty = 1.0;
        add(statusArea, gbc);

        gbc.gridy++;
        gbc.weighty = 0.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        JButton connectBtn = new JButton("Connect");
        connectBtn.addActionListener(e -> {
            String host = hostField.getText().trim();
            int port;
            try {
                port = Integer.parseInt(portField.getText().trim());
            }
            catch (NumberFormatException ex) {
                statusArea.setText("Invalid port number.");
                return;
            }
            connectToBroker(host, port);
        });
        add(connectBtn, gbc);

        revalidate();
        repaint();
    }

    private void showConnectedPanel(String host, int port) {
        removeAll();
        setLayout(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;

        gbc.gridx = 0; gbc.gridy = 0;
        gbc.gridwidth = 2;
        statusArea = new JTextArea("Connected to " + host + ":" + port
                + "\nPublishing to: " + topicPrefix + "/<vehicle>/logbook"
                + "\nSubscribed to: " + topicPrefix + "/in/#");
        statusArea.setEditable(false);
        statusArea.setLineWrap(true);
        statusArea.setWrapStyleWord(true);
        statusArea.setBackground(getBackground());
        gbc.fill = GridBagConstraints.BOTH;
        gbc.weighty = 1.0;
        add(statusArea, gbc);

        gbc.gridy++;
        gbc.weighty = 0.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        JButton disconnectBtn = new JButton("Disconnect");
        disconnectBtn.addActionListener(e -> {
            mqttClient.close();
            showConnectionForm();
        });
        add(disconnectBtn, gbc);

        revalidate();
        repaint();
    }
}
