package pt.lsts.neptus.plugins.server;

import com.google.common.eventbus.Subscribe;
import pt.lsts.imc.CoverArea;
import pt.lsts.imc.IMCDefinition;
import pt.lsts.imc.IMCMessage;
import pt.lsts.imc.PlanSpecification;
import pt.lsts.imc.PolygonVertex;
import pt.lsts.imc.SoiCommand;
import pt.lsts.imc.StateReport;
import pt.lsts.imc.VerticalProfile;
import pt.lsts.neptus.NeptusLog;
import pt.lsts.neptus.comm.manager.imc.ImcSystem;
import pt.lsts.neptus.comm.manager.imc.ImcSystemsHolder;
import pt.lsts.neptus.console.ConsoleLayout;
import pt.lsts.neptus.console.ConsolePanel;
import pt.lsts.neptus.console.notifications.Notification;
import pt.lsts.neptus.mp.MapChangeEvent;
import pt.lsts.neptus.plugins.ConfigurationListener;
import pt.lsts.neptus.plugins.NeptusProperty;
import pt.lsts.neptus.plugins.PluginDescription;
import pt.lsts.neptus.plugins.Popup;
import pt.lsts.neptus.types.coord.LocationType;
import pt.lsts.neptus.types.map.MapGroup;
import pt.lsts.neptus.types.map.MapType;
import pt.lsts.neptus.types.map.PathElement;
import pt.lsts.neptus.types.vehicle.VehiclesHolder;

import javax.swing.*;
import java.awt.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * @author João Bogas
 */
@PluginDescription(name = "Server Interface")
@Popup(pos = Popup.POSITION.CENTER, width = 250, height = 250, accelerator = 'Y')
public class ServerInterface extends ConsolePanel implements ConfigurationListener {

    @NeptusProperty(name = "Host IP", userLevel = NeptusProperty.LEVEL.REGULAR, description = "IP address of the remote server to connect to.")
    private String lastHost = "127.0.0.1";
    @NeptusProperty(name = "Port", userLevel = NeptusProperty.LEVEL.REGULAR, description = "TCP port of the remote server to connect to.")
    private int lastPort = 6005;
    @NeptusProperty(name = "Profile CSV Path", userLevel = NeptusProperty.LEVEL.REGULAR, description = "File path for logging incoming VerticalProfile messages.")
    private String profileCsvPath = "log/vertical_profiles.csv";

    private final ImcTcpClient client = new ImcTcpClient(IMCDefinition.getInstance());
    private final List<LocationType> op_area = new ArrayList<>();
    private final Map<Integer, SystemInfo> systems = new HashMap<>();
    private final ProfileCsvLogger profileLogger;
    private JTextArea statusLabel;
    private JTextArea systemsListArea;
    private JTextField ipField;
    private JTextField portField;
    private PathElement map_area;

    public ServerInterface(ConsoleLayout console) {
        super(console);
        profileLogger = new ProfileCsvLogger(profileCsvPath);
    }

    private boolean invalidSystem(int src) {
        return !systems.containsKey(src);
    }

    public void propertiesChanged() {
        profileLogger.setCsvPath(profileCsvPath);
    }

    @Subscribe
    public void onSoiCommand(SoiCommand msg) {

        int id = msg.getSrc();
        if (invalidSystem(id)) {
            return;
        }

        SystemInfo info = systems.get(id);
        if (msg.getCommand() == SoiCommand.COMMAND.RESUME && msg.getType() == SoiCommand.TYPE.SUCCESS) {
            info.setState(true);
        }

        sendMessage(msg);
    }

    @Subscribe
    public void onStateReport(StateReport msg) {
        if (invalidSystem(msg.getSrc())) {
            return;
        }

        sendMessage(msg);
    }

    @Subscribe
    public void onVerticalProfile(VerticalProfile msg) {
        profileLogger.log(msg);
        if (invalidSystem(msg.getSrc())) {
            return;
        }

        sendMessage(msg);
    }

    void
    notify(String _title, String message, int level) {

        switch (level) {
            case WARN_LEVEL:
                getConsole().post(Notification.warning(_title, message));
                break;
            case ERROR_LEVEL:
                getConsole().post(Notification.error(_title, message));
                break;
            default:
                getConsole().post(Notification.info(_title, message));
                break;
        }
    }


    @Override
    public void initSubPanel() {
        showConnectionForm();
    }

    private void setOperationalArea(CoverArea area) {

        if (!op_area.isEmpty()) {
            op_area.clear();
        }

        double lat = Math.toDegrees(area.getLat());
        double lon = Math.toDegrees(area.getLon());
        op_area.add(new LocationType(lat, lon));

        NeptusLog.pub().debug("setOperationalArea {}, {}", lat, lon);

        for (PolygonVertex vertex : area.getPolygon()) {
            lat = Math.toDegrees(vertex.getLat());
            lon = Math.toDegrees(vertex.getLon());

            op_area.add(new LocationType(lat, lon));
            NeptusLog.pub().debug("New point - {}, {}", lat, lon);
        }

        addMapElement();
    }

    public void addMapElement() {

        MapGroup mg = MapGroup.getMapGroupInstance(getConsole().getMission());

        MapType map = mg.getMaps()[0];
        NeptusLog.pub().debug("Map list size: {}", mg.getMaps().length);

        if (map_area != null) {
            NeptusLog.pub().warn("Operational area already exists!");
            map_area = null;

            sendMapEvent(map, MapChangeEvent.OBJECT_REMOVED);
        }

        LocationType first = op_area.get(0);
        map_area = new PathElement(mg, map, first);

        NeptusLog.pub().debug("Added first point: {}, {}", first.getLatitudeDegs(), first.getLongitudeDegs());
        map_area.setFilled(true);
        map_area.setShape(true);
        map_area.setId("Operational_Area");
        map_area.setMyColor(new Color(255, 255, 0, 128));
        map_area.addPoint(0, 0, 0, false);
        map.addObject(map_area);

        sendMapEvent(map, MapChangeEvent.OBJECT_ADDED);

        for (int idx = 1; idx < op_area.size(); idx++) {

            LocationType point = op_area.get(idx);
            map_area.addPoint(point);

            NeptusLog.pub().debug("New point: {} {}", point.getLatitudeDegs(), point.getLongitudeDegs());

            sendMapEvent(map, MapChangeEvent.OBJECT_CHANGED);
        }
    }

    private void sendMapEvent(MapType map, int eventType) {
        MapChangeEvent changeEvent = new MapChangeEvent(eventType);
        changeEvent.setChangedObject(map_area);
        changeEvent.setSourceMap(map);
        map.warnChangeListeners(changeEvent);
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
        ipField = new JTextField(lastHost, 10);
        add(ipField, gbc);

        gbc.gridx = 0;
        gbc.gridy++;
        add(new JLabel("Port:"), gbc);
        gbc.gridx = 1;
        portField = new JTextField(String.valueOf(lastPort), 5);
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
        JButton getInstructionsButton = new JButton("Add System");
        getInstructionsButton.addActionListener(e -> promptToAddSystem());
        add(getInstructionsButton, gbc);

        gbc.gridy = 2;
        gbc.weighty = 1.0;
        gbc.fill = GridBagConstraints.BOTH;

        systemsListArea = new JTextArea();
        systemsListArea.setEditable(false);
        systemsListArea.setBorder(BorderFactory.createTitledBorder("Monitored Systems"));
        updateSystemsListUI();

        add(new JScrollPane(systemsListArea), gbc);

        gbc.gridy = 3;
        gbc.weighty = 0.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        JButton stopButton = new JButton("Stop");
        stopButton.addActionListener(e -> {
            NeptusLog.pub().debug("Request SOI to stop");
            cleanSubPanel();
        });
        add(stopButton, gbc);

        revalidate();
        repaint();
    }

    private void promptToAddSystem() {

        ImcSystem[] allSystems = ImcSystemsHolder.lookupAllSystems();
        List<String> allVehicles = Arrays.asList(VehiclesHolder.getVehiclesArray());

        List<ImcSystem> availableSystems = Arrays.stream(allSystems)
                .filter(sys -> !systems.containsKey(sys.getId().intValue()))
                .filter(sys -> allVehicles.contains(sys.getName()))
                .sorted(Comparator.comparing(ImcSystem::getName))
                .collect(Collectors.toList());

        Object selected = JOptionPane.showInputDialog(
                this,
                "Select a system to monitor:",
                "Add System",
                JOptionPane.QUESTION_MESSAGE,
                null,
                availableSystems.toArray(),
                !availableSystems.isEmpty() ? availableSystems.get(0) : null
        );

        if (!(selected instanceof ImcSystem)) {
            return;
        }

        ImcSystem sys = (ImcSystem) selected;
        int id = sys.getId().intValue();
        String name = sys.getName();

        systems.put(id, new SystemInfo(name, false));
        NeptusLog.pub().debug("Added System to monitor {} ({})", name, id);

        // Refresh the UI
        updateSystemsListUI();

        LocationType sys_loc = sys.getLocation();
        long sys_ts = sys.getLocationTimeMillis();
        long cur_time = System.currentTimeMillis();

        Duration delta = Duration.ofMillis(Math.abs(cur_time - sys_ts));

        if (delta.getSeconds() > 60) {

            String result = String.format("System location expired by %s secs. Will wait for a new one", delta.getSeconds());

            getConsole().post(Notification.warning("Title?", result));
            return;
        }

        // Simulate a StateReport to notify listeners
        StateReport sr = new StateReport();
        sr.setSrc(id);
        sr.setStime(sys_ts);
        sr.setLatitude(sys_loc.getLatitudeDegs());
        sr.setLongitude(sys_loc.getLongitudeDegs());
        sendMessage(sr);
    }

    private void updateSystemsListUI() {
        if (systemsListArea == null) {
            return;
        }

        StringBuilder sb = new StringBuilder();
        if (systems.isEmpty()) {
            sb.append("No systems added.");
        }
        else {
            for (SystemInfo vec : systems.values()) {
                sb.append("- ").append(vec.getName()).append("\n");
            }
        }

        systemsListArea.setText(sb.toString());
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

            lastHost = serverIp;
            lastPort = serverPort;

            SwingUtilities.invokeLater(() -> showConnectedPanel(serverIp, serverPort));
        }
        catch (Exception e) {
            SwingUtilities.invokeLater(() -> statusLabel.setText("Connection failed: " + e.getMessage()));
            NeptusLog.pub().error("Could not connect to {}:{}", serverIp, serverPort);
        }
    }

    private void sendMessage(IMCMessage msg) {
        try {
            NeptusLog.pub().debug("Sending message: {}", msg);

            client.send(msg);
        }
        catch (Exception e) {
            NeptusLog.pub().warn("Failed to send message: {}", e.getMessage());
        }
    }

    private void handleIncoming(IMCMessage msg, String remote) {
        NeptusLog.pub().trace("Received message: {} from {}", msg.getAbbrev(), remote);

        if (msg.getMgid() == CoverArea.ID_STATIC) {
            try {
                setOperationalArea(CoverArea.clone(msg));
            }
            catch (Exception e) {
                NeptusLog.pub().error("Failed to set operational area: {}", e.getMessage());
            }
            return;
        }

        int id = msg.getDst();
        if (invalidSystem(id)) {
            NeptusLog.pub().warn("Invalid system ID destination: {}", id);
            return;
        }

        SystemInfo sys = systems.get(id);
        send(sys.getName(), msg);

        if (msg.getMgid() != PlanSpecification.ID_STATIC) {
            return;
        }

        getConsole().getImcMsgManager().broadcastToCCUs(msg);
        getConsole().getImcMsgManager().postInternalMessage("Plugin-Server", msg);
        NeptusLog.pub().debug("Sharing plan: {}", msg);
        notify("SOI-DOURO", "New plan for " + sys.getName() + " received!", WARN_LEVEL);
    }

    public void serverDisconnected(String remote, Exception cause) {
        NeptusLog.pub().debug("Disconnected from {}: {}", remote, cause.getMessage());
        systems.clear();
        SwingUtilities.invokeLater(this::showConnectionForm);
    }

    @Override
    public void cleanSubPanel() {
        NeptusLog.pub().warn("Closing connection form");
        profileLogger.close();
        client.close();

        systems.clear();
        initSubPanel();
    }
}

