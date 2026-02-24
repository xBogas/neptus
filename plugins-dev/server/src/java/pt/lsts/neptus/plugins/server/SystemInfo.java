package pt.lsts.neptus.plugins.server;

public class SystemInfo {
    private final String name;
    private boolean state;

    public SystemInfo(String name, boolean state) {
        this.name = name;
        this.state = state;
    }

    public String getName() {
        return name;
    }

    public boolean isActive() {
        return state;
    }

    public void setState(boolean state) {
        this.state = state;
    }
}
