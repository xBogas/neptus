package pt.lsts.neptus.plugins.mqtt;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import pt.lsts.neptus.NeptusLog;

/**
 * Thin wrapper around Eclipse Paho that manages connection and pub/sub lifecycle.
 *
 * @author João Bogas
 */
public class MqttBrokerClient {

    public interface MessageListener {
        void onMessage(String topic, String payload);

        void onDisconnect(String broker, Throwable cause);
    }

    private MqttClient client;
    private MessageListener listener;

    public void addListener(MessageListener l) {
        this.listener = l;
    }

    public void connect(String host, int port, String clientId, int keepAlive) throws MqttException {
        String brokerUri = "tcp://" + host + ":" + port;
        client = new MqttClient(brokerUri, clientId, new MemoryPersistence());

        MqttConnectOptions opts = new MqttConnectOptions();
        opts.setCleanSession(true);
        opts.setKeepAliveInterval(keepAlive);
        opts.setAutomaticReconnect(false);

        client.setCallback(new MqttCallback() {
            @Override
            public void connectionLost(Throwable cause) {
                NeptusLog.pub().warn("MQTT connection lost: {}", cause.getMessage());
                if (listener != null)
                    listener.onDisconnect(brokerUri, cause);
            }

            @Override
            public void messageArrived(String topic, MqttMessage message) {
                if (listener != null)
                    listener.onMessage(topic, new String(message.getPayload()));
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {
                // no-op
            }
        });

        client.connect(opts);
        NeptusLog.pub().info("Connected to MQTT broker at {}", brokerUri);
    }

    public void subscribe(String topicFilter, int qos) throws MqttException {
        if (client == null || !client.isConnected())
            throw new MqttException(MqttException.REASON_CODE_CLIENT_NOT_CONNECTED);
        client.subscribe(topicFilter, qos);
    }

    public void publish(String topic, String payload, int qos) {
        if (client == null || !client.isConnected()) {
            NeptusLog.pub().warn("MQTT publish skipped — not connected");
            return;
        }
        try {
            client.publish(topic, payload.getBytes(), qos, false);
        }
        catch (MqttException e) {
            NeptusLog.pub().warn("MQTT publish failed on topic {}: {}", topic, e.getMessage());
        }
    }

    public boolean isConnected() {
        return client != null && client.isConnected();
    }

    public void close() {
        if (client == null)
            return;
        try {
            if (client.isConnected())
                client.disconnect();
            client.close();
        }
        catch (MqttException e) {
            NeptusLog.pub().warn("Error closing MQTT client: {}", e.getMessage());
        }
        finally {
            client = null;
        }
    }
}
