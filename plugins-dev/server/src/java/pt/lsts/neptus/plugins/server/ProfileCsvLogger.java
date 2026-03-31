package pt.lsts.neptus.plugins.server;

import pt.lsts.imc.ProfileSample;
import pt.lsts.imc.VerticalProfile;
import pt.lsts.neptus.NeptusLog;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.TimeZone;
import java.util.Vector;

/**
 * Logs incoming {@link VerticalProfile} messages to a CSV file.
 * The file is continuously appended to as new profiles arrive.
 *
 * @author João Bogas
 */
public class ProfileCsvLogger {

    private static final String CSV_HEADER = "timestamp,datetime_utc,source,lat,lon,parameter,num_samples,samples";

    private final SimpleDateFormat utcDateFormat;
    private BufferedWriter writer;
    private String csvPath;

    public ProfileCsvLogger(String csvPath) {
        this.csvPath = csvPath;
        this.utcDateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
        this.utcDateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    public void setCsvPath(String csvPath) {
        this.csvPath = csvPath;
    }

    public synchronized void log(VerticalProfile msg) {
        try {
            ensureWriterOpen();

            double epochSecs = msg.getTimestamp();
            String dateStr = utcDateFormat.format(msg.getDate());
            String source = msg.getSourceName();
            double lat = msg.getLat();
            double lon = msg.getLon();
            String parameter = msg.getParameter() != null ? msg.getParameter().name() : "UNKNOWN";

            Vector<ProfileSample> samples = msg.getSamples();
            int numSamples = samples != null ? samples.size() : 0;

            StringBuilder samplesStr = new StringBuilder();
            if (samples != null) {
                for (int i = 0; i < samples.size(); i++) {
                    ProfileSample s = samples.get(i);
                    if (i > 0) {
                        samplesStr.append(";");
                    }
                    samplesStr.append(s.getDepth()).append(":").append(s.getAvg());
                }
            }

            writer.write(String.format("%.3f,%s,%s,%.6f,%.6f,%s,%d,%s",
                    epochSecs, dateStr, source, lat, lon, parameter, numSamples, samplesStr));
            writer.newLine();
            writer.flush();
        }
        catch (IOException e) {
            NeptusLog.pub().warn("Failed to log VerticalProfile to CSV: {}", e.getMessage());
        }
    }

    public synchronized void close() {
        if (writer == null) {
            return;
        }

        try {
            writer.flush();
            writer.close();
        }
        catch (IOException e) {
            NeptusLog.pub().warn("Failed to close profile CSV writer: {}", e.getMessage());
        }
        finally {
            writer = null;
        }
    }

    private void ensureWriterOpen() throws IOException {
        if (writer != null) {
            return;
        }

        File csvFile = new File(csvPath);
        File parentDir = csvFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            if (!parentDir.mkdirs()) {
                throw new IOException("Failed to create parent directory: " + parentDir);
            }
        }

        boolean isNewFile = !csvFile.exists();
        writer = new BufferedWriter(new FileWriter(csvFile, true));

        if (isNewFile) {
            writer.write(CSV_HEADER);
            writer.newLine();
            writer.flush();
        }
    }
}
