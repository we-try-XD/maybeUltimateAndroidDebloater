package it.debloater;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.List;
import java.util.regex.*;
import java.util.stream.*;
import java.util.zip.*;

public class Main {
    public static void main(String[] a) {
        try { 
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); 
        } catch (Exception ignored) {}
        SwingUtilities.invokeLater(() -> new Frame().setVisible(true));
    }
}

enum PkgState { 
    ENABLED("Enabled"), DISABLED("Disabled"), UNINSTALLED("Uninstalled");
    private final String l; 
    PkgState(String s) { l = s; } 
    public String toString() { return l; }
    static PkgState from(String s) {
        for(PkgState p : values()) if(p.l.equalsIgnoreCase(s)) return p;
        return ENABLED;
    }
}

class Pkg { 
    boolean sel; 
    String n; 
    String ai = ""; 
    PkgState s; 
    Pkg(String n, PkgState s) { this.n = n; this.s = s; } 
    Pkg(String n, PkgState s, String ai) { this.n = n; this.s = s; this.ai = ai; } 
}

class Tools {
    File d = new File(System.getProperty("user.home"), ".debloater"); 
    String st = "";
    
    Tools(JFrame p) { 
        d.mkdirs(); 
        if(!ok()) install(p);
    }
    
    boolean ok() { return adb() != null && jadx() != null; }
    
    String adb() { 
        File f = new File(d, "platform-tools/adb" + (isWin() ? ".exe" : ""));
        return f.exists() ? f.getAbsolutePath() : null;
    }
    
    String jadx() { 
        File f = new File(d, "jadx/bin/jadx" + (isWin() ? ".bat" : ""));
        return f.exists() ? f.getAbsolutePath() : null;
    }
    
    boolean isWin() { return System.getProperty("os.name").toLowerCase().contains("win"); }
    
    void install(JFrame p) {
        JDialog dlg = new JDialog(p, "Tools Setup", true); 
        dlg.setSize(380, 130); 
        dlg.setLocationRelativeTo(p);
        JLabel lbl = new JLabel("Downloading tools...", SwingConstants.CENTER); 
        dlg.add(lbl);
        
        javax.swing.Timer t = new javax.swing.Timer(200, e -> {
            lbl.setText("<html><center>" + st + "</center></html>"); 
            if(st.contains("Done") || st.contains("Error")) {
                ((javax.swing.Timer)e.getSource()).stop();
                dlg.dispose();
            }
        });
        t.start();
        
        new SwingWorker<Void, String>() {
            protected Void doInBackground() {
                try { 
                    publish("Downloading ADB..."); 
                    dl("https://dl.google.com/android/repository/platform-tools-latest-" + (isWin() ? "windows" : "linux") + ".zip", new File(d, "t.zip")); 
                    unzip(new File(d, "t.zip"), d); 
                    new File(d, "t.zip").delete();

                    publish("Downloading JADX..."); 
                    String j = http("https://api.github.com/repos/skylot/jadx/releases/latest"); 
                    // Regex to intercept the zip file for JADX releases
                    String u = ex(j, "https://github.com/skylot/jadx/releases/download/[^\"]+/jadx-[^\"]+\\.zip"); 
                    if (u != null) {
                        File jadxDir = new File(d, "jadx");
                        jadxDir.mkdirs();
                        File zipFile = new File(d, "j.zip");
                        dl(u, zipFile); 
                        unzip(zipFile, jadxDir); 
                        zipFile.delete(); 
                        
                        // Handle nested folders if the zip file contains a subfolder
                        fixJadxStructure(jadxDir);
                        publish("Done!"); 
                    } else {
                        publish("Error: Could not find download link for JADX.");
                    }
                } catch(Exception e) {
                    publish("Error: " + e.getMessage());
                } 
                return null;
            }
            protected void process(List<String> c) { st = c.get(c.size() - 1); }
        }.execute(); 
        dlg.setVisible(true);
    }
    
    String http(String u) throws Exception {
        return HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .build()
                .send(
                    HttpRequest.newBuilder()
                        .uri(URI.create(u))
                        .header("User-Agent", "Mozilla/5.0")
                        .header("Accept", "application/vnd.github+json")
                        .build(), 
                    HttpResponse.BodyHandlers.ofString()
                ).body();
    }
    
    String ex(String json, String patternRegex) {
        Matcher m = Pattern.compile(patternRegex).matcher(json);
        return m.find() ? m.group(0) : null;
    }
    
    void dl(String u, File f) throws Exception {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build();
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(u)).header("User-Agent", "Mozilla/5.0").build();
        HttpResponse<InputStream> res = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
        
        if (res.statusCode() != 200) {
            throw new IOException("Download failed with HTTP code " + res.statusCode());
        }
        
        try(InputStream in = res.body(); FileOutputStream out = new FileOutputStream(f)) {
            in.transferTo(out);
        }
    }
    
    void unzip(File z, File dest) throws IOException {
        try(ZipInputStream zi = new ZipInputStream(new FileInputStream(z))) {
            ZipEntry e;
            while((e = zi.getNextEntry()) != null) {
                File f = new File(dest, e.getName());
                if(e.isDirectory()) {
                    f.mkdirs();
                } else {
                    f.getParentFile().mkdirs();
                    Files.copy(zi, f.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
    
    // Normalizes JADX structure if zip was extracted into a subfolder
    void fixJadxStructure(File jadxDir) {
        File binDir = new File(jadxDir, "bin");
        if (!binDir.exists()) {
            File[] subFiles = jadxDir.listFiles(File::isDirectory);
            if (subFiles != null && subFiles.length == 1) {
                File subDir = subFiles[0];
                File innerBin = new File(subDir, "bin");
                if (innerBin.exists()) {
                    for (File child : subDir.listFiles()) {
                        child.renameTo(new File(jadxDir, child.getName()));
                    }
                    subDir.delete();
                }
            }
        }
        // Assign execution permissions for Linux/Mac systems
        if (!isWin()) {
            File exec = new File(jadxDir, "bin/jadx");
            if (exec.exists()) exec.setExecutable(true, false);
        }
    }
    
    void del(Path p) {
        try {
            Files.walk(p).sorted(Comparator.reverseOrder()).forEach(x -> {
                try { Files.delete(x); } catch(Exception ignored) {}
            });
        } catch(Exception ignored) {}
    }
}

class Adb {
    String p = "adb"; 
    void set(String s) { if(s != null && !s.isBlank()) p = s; }
    
    boolean ok() {
        try { return new ProcessBuilder(p, "version").start().waitFor() == 0; } catch(Exception e) { return false; }
    }
    
    List<String> devs() throws Exception {
        List<String> r = new ArrayList<>();
        Process pr = new ProcessBuilder(p, "devices").start();
        try(BufferedReader br = new BufferedReader(new InputStreamReader(pr.getInputStream()))) {
            String l;
            while((l = br.readLine()) != null) {
                if(l.endsWith("device")) r.add(l.split("\\s+")[0]);
            }
        }
        return r;
    }
    
    Map<String, PkgState> list(String s) throws Exception {
        // Currently installed/active packages
        Set<String> installed = new HashSet<>(pm(s, "list", "packages"));
        // Disabled packages
        Set<String> disabled = new HashSet<>(pm(s, "list", "packages", "-d"));
        // All packages including uninstalled ones for current user (--user 0)
        Set<String> allUninstalledIncluded = new HashSet<>(pm(s, "list", "packages", "-u"));
        
        Map<String, PkgState> r = new TreeMap<>();
        
        // 1. Process packages currently on the system
        for(String pkg : installed) {
            if(disabled.contains(pkg)) {
                r.put(pkg, PkgState.DISABLED);
            } else {
                r.put(pkg, PkgState.ENABLED);
            }
        }
        
        // 2. Process packages removed by user (present in -u but not in standard list)
        for(String pkg : allUninstalledIncluded) {
            if(!installed.contains(pkg)) {
                r.put(pkg, PkgState.UNINSTALLED);
            }
        }
        
        return r;
    }
    
    List<String> pm(String s, String... a) throws Exception {
        List<String> c = new ArrayList<>(List.of(p));
        if(s != null && !s.isEmpty()) { c.add("-s"); c.add(s); }
        c.add("shell"); c.add("pm");
        c.addAll(Arrays.asList(a));
        
        Process pr = new ProcessBuilder(c).start();
        List<String> r = new ArrayList<>();
        try(BufferedReader br = new BufferedReader(new InputStreamReader(pr.getInputStream()))) {
            String l;
            while((l = br.readLine()) != null) {
                if(l.startsWith("package:")) r.add(l.substring(8).trim());
            }
        }
        return r;
    }
    
    void run(String s, String... a) throws Exception {
        List<String> c = new ArrayList<>(List.of(p));
        if(s != null && !s.isEmpty()) { c.add("-s"); c.add(s); }
        c.add("shell");
        c.addAll(Arrays.asList(a));
        if(new ProcessBuilder(c).start().waitFor() != 0) throw new IOException("Operation Failed");
    }
}

class Gemini {
    String k, m = "gemini-3.5-flash-lite", pt; 
    HttpClient c = HttpClient.newHttpClient();
    Gemini(String k) { this.k = k; }
    
    String ask(String pkg, String sum) throws Exception {
        String pr = (pt != null ? pt : "Analyze Android package {package}. Data: {summary}. Rate [SAFE]/[CAUTION]/[UNSAFE]. Brief.")
                    .replace("{package}", pkg).replace("{summary}", sum == null ? "" : sum);
        String b = "{\"contents\":[{\"parts\":[{\"text\":\"" + pr.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"}]}]}";
        
        HttpRequest r = HttpRequest.newBuilder()
                .uri(URI.create("https://generativelanguage.googleapis.com/v1beta/models/" + m + ":generateContent?key=" + k))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(b))
                .timeout(Duration.ofSeconds(30)).build();
                
        HttpResponse<String> res = c.send(r, HttpResponse.BodyHandlers.ofString());
        if(res.statusCode() != 200) return "[ERR " + res.statusCode() + "]";
        
        Matcher mt = Pattern.compile("\"text\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"").matcher(res.body());
        StringBuilder sb = new StringBuilder(); 
        while(mt.find()) sb.append(mt.group(1).replace("\\n", "\n").replace("\\\"", "\""));
        return sb.length() > 0 ? sb.toString() : "[No Response]";
    }
}

class Frame extends JFrame {
    Adb adb = new Adb(); 
    Tools tools; 
    Gemini gem;
    
    JComboBox<String> dev = new JComboBox<>(), filt = new JComboBox<>(new String[]{"All", "Enabled", "Disabled", "Uninstalled"});
    JTextField tMod = new JTextField("gemini-3.5-flash-lite");
    JPasswordField tKey = new JPasswordField(); 
    JTextField tSearch = new JTextField();
    
    DefaultTableModel model = new DefaultTableModel(new Object[]{"", "Package Name", "State", "AI Info", "Details"}, 0) {
        public boolean isCellEditable(int r, int c) { return c == 0 || c == 4; }
        public Class<?> getColumnClass(int c) { return c == 0 ? Boolean.class : String.class; }
    };
    
    JTable table = new JTable(model); 
    JProgressBar prog = new JProgressBar(); 
    JLabel stat = new JLabel("Ready");
    List<Pkg> pkgs = new ArrayList<>(); 
    String prompt = null;

    Frame() {
        super("ADB Debloater + AI"); 
        setSize(1100, 650); 
        setDefaultCloseOperation(EXIT_ON_CLOSE); 
        setLocationRelativeTo(null);
        
        tools = new Tools(this);
        if (tools.adb() != null) adb.set(tools.adb());
        
        // --- CONFIGURATION AND CONTROL PANEL ---
        JPanel pnlHeader = new JPanel(new GridBagLayout());
        pnlHeader.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(3, 4, 3, 4); 
        g.fill = GridBagConstraints.HORIZONTAL;

        // Row 0: API Key, Model, Device, Refresh, Load Packages, Check Tools
        g.gridy = 0; g.gridx = 0; g.weightx = 0; pnlHeader.add(new JLabel("API Key:"), g);
        g.gridx = 1; g.weightx = 0.3; pnlHeader.add(tKey, g);

        g.gridx = 2; g.weightx = 0; pnlHeader.add(new JLabel("Model:"), g);
        g.gridx = 3; g.weightx = 0.2; pnlHeader.add(tMod, g);

        g.gridx = 4; g.weightx = 0; pnlHeader.add(new JLabel("Device:"), g);
        g.gridx = 5; g.weightx = 0.2; pnlHeader.add(dev, g);

        JPanel pnlDevBtns = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        JButton bRef = new JButton("Refresh"); bRef.addActionListener(e -> refresh());
        JButton bLoad = new JButton("Load Packages"); bLoad.addActionListener(e -> load());
        JButton bCheckTools = new JButton("Check Tools"); bCheckTools.addActionListener(e -> checkToolsStatus());
        
        pnlDevBtns.add(bRef); 
        pnlDevBtns.add(bLoad);
        pnlDevBtns.add(bCheckTools);
        g.gridx = 6; g.weightx = 0; pnlHeader.add(pnlDevBtns, g);

        // Row 1: Filter, Search, Select all / Deselect all
        g.gridy = 1; g.gridx = 0; g.weightx = 0; pnlHeader.add(new JLabel("Filter:"), g);
        g.gridx = 1; g.weightx = 0.1; pnlHeader.add(filt, g); filt.addActionListener(e -> applyFilt());

        g.gridx = 2; g.weightx = 0; pnlHeader.add(new JLabel("Search:"), g);
        g.gridx = 3; g.gridwidth = 3; g.weightx = 0.5; pnlHeader.add(tSearch, g); g.gridwidth = 1;
        
        tSearch.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { applyFilt(); }
            public void removeUpdate(DocumentEvent e) { applyFilt(); }
            public void changedUpdate(DocumentEvent e) { applyFilt(); }
        });

        JPanel pnlSelBtns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        JButton bSel = new JButton("Select all"); bSel.addActionListener(e -> setAllSelected(true));
        JButton bDes = new JButton("Deselect all"); bDes.addActionListener(e -> setAllSelected(false));
        pnlSelBtns.add(bSel); pnlSelBtns.add(bDes);
        g.gridx = 6; g.weightx = 0; pnlHeader.add(pnlSelBtns, g);

        setLayout(new BorderLayout());
        add(pnlHeader, BorderLayout.NORTH);

        // --- PACKAGE TABLE ---
        table.setRowHeight(24);
        table.getColumnModel().getColumn(0).setMaxWidth(40);
        table.getColumnModel().getColumn(4).setMaxWidth(80);
        
        table.getColumnModel().getColumn(4).setCellRenderer(new ButtonRenderer());
        table.getColumnModel().getColumn(4).setCellEditor(new ButtonEditor(new JCheckBox()));

        model.addTableModelListener(e -> {
            if (e.getColumn() == 0 && e.getFirstRow() >= 0) {
                int row = e.getFirstRow();
                Boolean checked = (Boolean) model.getValueAt(row, 0);
                String pkgName = (String) model.getValueAt(row, 1);
                pkgs.stream().filter(p -> p.n.equals(pkgName)).findFirst().ifPresent(p -> p.sel = checked);
            }
        });

        add(new JScrollPane(table), BorderLayout.CENTER);

        // --- ACTION PANEL AND STATUS BAR ---
        JPanel pnlSouth = new JPanel(new BorderLayout());
        JPanel pnlActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 5));
        
        String[] ops = {"Disable", "Enable", "Uninstall", "Restore", "Export CSV", "Import CSV", "AI: Selected", "AI: All", "AI: Missing", "Edit Prompt"};
        for(String o : ops) {
            JButton b = new JButton(o);
            b.addActionListener(e -> act(o));
            pnlActions.add(b);
        }
        
        prog.setPreferredSize(new Dimension(150, 20));
        prog.setVisible(false);
        pnlActions.add(prog);

        JPanel pnlStatus = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 2));
        pnlStatus.setBorder(BorderFactory.createEtchedBorder());
        pnlStatus.add(stat);

        pnlSouth.add(pnlActions, BorderLayout.CENTER);
        pnlSouth.add(pnlStatus, BorderLayout.SOUTH);
        
        add(pnlSouth, BorderLayout.SOUTH);
    }
    
    // Method to check JDK, ADB, and JADX tool status
    void checkToolsStatus() {
        StringBuilder sb = new StringBuilder();
        
        // 1. JDK / Java Runtime Check
        String javaVer = System.getProperty("java.version");
        String javaVendor = System.getProperty("java.vendor");
        sb.append("<b>JDK / Java Runtime:</b><br>");
        sb.append(" - Status: <font color='green'>INSTALLED</font><br>");
        sb.append(" - Version: ").append(javaVer).append(" (").append(javaVendor).append(")<br><br>");
        
        // 2. ADB Check
        sb.append("<b>ADB (Android Debug Bridge):</b><br>");
        if (adb.ok()) {
            sb.append(" - Status: <font color='green'>INSTALLED AND WORKING</font><br>");
        } else if (tools.adb() != null) {
            sb.append(" - Status: <font color='green'>PRESENT</font> in ").append(tools.adb()).append("<br>");
        } else {
            sb.append(" - Status: <font color='red'>NOT FOUND</font><br>");
        }
        
        // 3. JADX Check
        sb.append("<br><b>JADX Decompiler:</b><br>");
        String jadxPath = tools.jadx();
        if (jadxPath != null) {
            sb.append(" - Status: <font color='green'>PRESENT</font><br>");
            sb.append(" - Path: ").append(jadxPath).append("<br>");
        } else {
            sb.append(" - Status: <font color='red'>NOT FOUND</font><br>");
        }

        JEditorPane ep = new JEditorPane("text/html", "<html><body style='font-family:sans-serif; padding:10px;'>" + sb.toString() + "</body></html>");
        ep.setEditable(false);
        ep.setOpaque(false);
        JOptionPane.showMessageDialog(this, ep, "Tools Installation Status", JOptionPane.INFORMATION_MESSAGE);
    }
    
    void setAllSelected(boolean val) {
        for(int i = 0; i < model.getRowCount(); i++) {
            model.setValueAt(val, i, 0);
        }
    }
    
    void showAi(int r) {
        int mr = table.convertRowIndexToModel(r);
        String txt = (String) model.getValueAt(mr, 3);
        String pkg = (String) model.getValueAt(mr, 1);
        if(txt != null && !txt.isEmpty()) {
            JTextArea ta = new JTextArea(15, 50);
            ta.setText(txt);
            ta.setWrapStyleWord(true);
            ta.setLineWrap(true);
            ta.setCaretPosition(0);
            ta.setEditable(false);
            JOptionPane.showMessageDialog(this, new JScrollPane(ta), "AI Info - " + pkg, JOptionPane.INFORMATION_MESSAGE);
        } else {
            JOptionPane.showMessageDialog(this, "No AI information available for this package.", "AI Info", JOptionPane.WARNING_MESSAGE);
        }
    }
    
    void refresh() {
        stat.setText("Scanning devices...");
        new SwingWorker<List<String>, Void>() {
            protected List<String> doInBackground() throws Exception { return adb.devs(); }
            protected void done() {
                try {
                    dev.removeAllItems();
                    List<String> list = get();
                    for(String s : list) dev.addItem(s);
                    stat.setText(list.size() + " devices found");
                } catch(Exception e) { stat.setText("Device scan error"); }
            }
        }.execute();
    }
    
    void load() {
        String s = (String) dev.getSelectedItem();
        if (s == null) { JOptionPane.showMessageDialog(this, "Select a device first!"); return; }
        stat.setText("Loading packages...");
        
        new SwingWorker<Map<String, PkgState>, Void>() {
            protected Map<String, PkgState> doInBackground() throws Exception { return adb.list(s); }
            protected void done() {
                try {
                    pkgs.clear();
                    for(var e : get().entrySet()) pkgs.add(new Pkg(e.getKey(), e.getValue()));
                    applyFilt();
                    stat.setText(pkgs.size() + " packages loaded");
                } catch(Exception e) { stat.setText("Package loading error"); }
            }
        }.execute();
    }
    
    void applyFilt() {
        model.setRowCount(0);
        String q = tSearch.getText().toLowerCase().trim();
        int fi = filt.getSelectedIndex();
        
        for(Pkg p : pkgs) {
            if(fi == 1 && p.s != PkgState.ENABLED) continue;
            if(fi == 2 && p.s != PkgState.DISABLED) continue;
            if(fi == 3 && p.s != PkgState.UNINSTALLED) continue;
            if(!q.isEmpty() && !p.n.toLowerCase().contains(q)) continue;
            
            model.addRow(new Object[]{p.sel, p.n, p.s.toString(), p.ai, "Details"});
        }
    }
    
    void act(String op) {
        List<Pkg> sel = pkgs.stream().filter(p -> p.sel).collect(Collectors.toList());
        
        if(op.equals("Edit Prompt")) {
            String s = JOptionPane.showInputDialog(this, "Prompt Template ({package}, {summary}):", prompt);
            if(s != null) prompt = s; 
            return;
        }
        
        if(op.startsWith("AI")) {
            String k = new String(tKey.getPassword());
            if(k.isEmpty()) { JOptionPane.showMessageDialog(this, "Enter a valid API Key to use Gemini"); return; }
            gem = new Gemini(k); gem.m = tMod.getText(); gem.pt = prompt;
            
            List<Pkg> tgt = op.contains("All") ? pkgs : op.contains("Missing") ? 
                            pkgs.stream().filter(p -> p.ai.isEmpty() || p.ai.startsWith("[ERR")).collect(Collectors.toList()) : sel;
            
            if(tgt.isEmpty()) { JOptionPane.showMessageDialog(this, "No package selected or to process"); return; }
            
            prog.setVisible(true); 
            prog.setMaximum(tgt.size());
            prog.setValue(0);
            
            new SwingWorker<Void, Integer>() {
                int i = 0;
                protected Void doInBackground() {
                    for(Pkg p : tgt) {
                        try { p.ai = gem.ask(p.n, null); } catch(Exception e) { p.ai = "[ERR]"; }
                        publish(++i);
                        try { Thread.sleep(300); } catch(Exception ignored) {}
                    }
                    return null;
                }
                protected void process(List<Integer> chunks) {
                    prog.setValue(chunks.get(chunks.size() - 1));
                    applyFilt();
                }
                protected void done() { 
                    prog.setVisible(false); 
                    stat.setText("AI Analysis Completed"); 
                }
            }.execute(); 
            return;
        }

        if (op.equals("Export CSV")) {
            JFileChooser fc = new JFileChooser();
            if (fc.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
                File file = fc.getSelectedFile();
                if (!file.getName().endsWith(".csv")) file = new File(file.getAbsolutePath() + ".csv");
                try (PrintWriter w = new PrintWriter(file)) {
                    w.println("package,state,ai");
                    for (Pkg x : pkgs) w.println(x.n + "," + x.s + ",\"" + x.ai.replace("\"", "\"\"") + "\"");
                    JOptionPane.showMessageDialog(this, "File saved successfully!");
                } catch (Exception e) {
                    JOptionPane.showMessageDialog(this, "Error saving CSV: " + e.getMessage());
                }
            }
            return;
        }

        if (op.equals("Import CSV")) {
            JFileChooser fc = new JFileChooser();
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                File file = fc.getSelectedFile();
                try (BufferedReader br = new BufferedReader(new FileReader(file))) {
                    String line;
                    boolean firstLine = true;
                    pkgs.clear();
                    while ((line = br.readLine()) != null) {
                        if (firstLine) { firstLine = false; continue; }
                        String[] parts = line.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)", -1);
                        if (parts.length >= 2) {
                            String pkgName = parts[0].trim();
                            PkgState state = PkgState.from(parts[1].trim());
                            String aiInfo = parts.length > 2 ? parts[2].replace("\"", "").trim() : "";
                            pkgs.add(new Pkg(pkgName, state, aiInfo));
                        }
                    }
                    applyFilt();
                    stat.setText(pkgs.size() + " packages imported from CSV");
                } catch (Exception e) {
                    JOptionPane.showMessageDialog(this, "Error importing CSV: " + e.getMessage());
                }
            }
            return;
        }

        if(sel.isEmpty()) { JOptionPane.showMessageDialog(this, "No packages selected for operation"); return; }
        
        String s = (String) dev.getSelectedItem();
        
        new SwingWorker<Void, Void>() {
            protected Void doInBackground() {
                for(Pkg p : sel) {
                    try {
                        if(op.equals("Disable")) { adb.run(s, "pm", "disable-user", "--user", "0", p.n); p.s = PkgState.DISABLED; }
                        if(op.equals("Enable")) { adb.run(s, "pm", "enable", p.n); p.s = PkgState.ENABLED; }
                        if(op.equals("Uninstall")) { adb.run(s, "pm", "uninstall", "--user", "0", p.n); p.s = PkgState.UNINSTALLED; }
                        if(op.equals("Restore")) { adb.run(s, "cmd", "package", "install-existing", p.n); p.s = PkgState.ENABLED; }
                    } catch(Exception ignored) {}
                }
                return null;
            }
            protected void done() { 
                applyFilt(); 
                stat.setText("Operation " + op + " completed"); 
            }
        }.execute();
    }

    // --- SUPPORT CLASSES FOR TABLE BUTTON ---
    class ButtonRenderer extends JButton implements TableCellRenderer {
        public ButtonRenderer() { setOpaque(true); }
        public Component getTableCellRendererComponent(JTable t, Object v, boolean isSelected, boolean hasFocus, int r, int c) {
            setText((v == null) ? "Details" : v.toString());
            return this;
        }
    }

    class ButtonEditor extends DefaultCellEditor {
        private JButton button;
        private String label;
        private boolean isPushed;
        private int row;

        public ButtonEditor(JCheckBox checkBox) {
            super(checkBox);
            button = new JButton();
            button.setOpaque(true);
            button.addActionListener(e -> fireEditingStopped());
        }

        public Component getTableCellEditorComponent(JTable t, Object v, boolean isSelected, int r, int c) {
            label = (v == null) ? "Details" : v.toString();
            button.setText(label);
            row = r;
            isPushed = true;
            return button;
        }

        public Object getCellEditorValue() {
            if (isPushed) {
                SwingUtilities.invokeLater(() -> showAi(row));
            }
            isPushed = false;
            return label;
        }
    }
}