package it.debloater;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.*;
import java.awt.*;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.List;
import java.util.regex.*;
import java.util.stream.Collectors;
import java.util.zip.*;

public class Main {
    public static void main(String[] a) {
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) {}
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
    String n, ai = ""; 
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
                    String u = ex(j, "https://github.com/skylot/jadx/releases/download/[^\"]+/jadx-[^\"]+\\.zip"); 
                    if (u != null) {
                        File jadxDir = new File(d, "jadx");
                        jadxDir.mkdirs();
                        File zipFile = new File(d, "j.zip");
                        dl(u, zipFile); 
                        unzip(zipFile, jadxDir); 
                        zipFile.delete(); 
                        fixJadxStructure(jadxDir);
                        publish("Done!"); 
                    } else publish("Error: JADX link missing");
                } catch(Exception e) { publish("Error: " + e.getMessage()); } 
                return null;
            }
            protected void process(List<String> c) { st = c.get(c.size() - 1); }
        }.execute(); 
        dlg.setVisible(true);
    }
    
    String http(String u) throws Exception {
        return HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build().send(
            HttpRequest.newBuilder().uri(URI.create(u)).header("User-Agent", "Mozilla/5.0").header("Accept", "application/vnd.github+json").build(), 
            HttpResponse.BodyHandlers.ofString()
        ).body();
    }
    
    String ex(String json, String patternRegex) {
        Matcher m = Pattern.compile(patternRegex).matcher(json);
        return m.find() ? m.group(0) : null;
    }
    
    void dl(String u, File f) throws Exception {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build();
        HttpResponse<InputStream> res = client.send(HttpRequest.newBuilder().uri(URI.create(u)).header("User-Agent", "Mozilla/5.0").build(), HttpResponse.BodyHandlers.ofInputStream());
        if (res.statusCode() != 200) throw new IOException("HTTP " + res.statusCode());
        try(InputStream in = res.body(); FileOutputStream out = new FileOutputStream(f)) { in.transferTo(out); }
    }
    
    void unzip(File z, File dest) throws IOException {
        try(ZipInputStream zi = new ZipInputStream(new FileInputStream(z))) {
            ZipEntry e;
            while((e = zi.getNextEntry()) != null) {
                File f = new File(dest, e.getName());
                if(e.isDirectory()) f.mkdirs();
                else {
                    f.getParentFile().mkdirs();
                    Files.copy(zi, f.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
    
    void fixJadxStructure(File jadxDir) {
        if (!new File(jadxDir, "bin").exists()) {
            File[] sub = jadxDir.listFiles(File::isDirectory);
            if (sub != null && sub.length == 1 && new File(sub[0], "bin").exists()) {
                for (File child : sub[0].listFiles()) child.renameTo(new File(jadxDir, child.getName()));
                sub[0].delete();
            }
        }
        if (!isWin()) {
            File exec = new File(jadxDir, "bin/jadx");
            if (exec.exists()) exec.setExecutable(true, false);
        }
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
            while((l = br.readLine()) != null) if(l.endsWith("device")) r.add(l.split("\\s+")[0]);
        }
        return r;
    }
    
    Map<String, PkgState> list(String s) throws Exception {
        Set<String> installed = new HashSet<>(pm(s, "list", "packages"));
        Set<String> disabled = new HashSet<>(pm(s, "list", "packages", "-d"));
        Set<String> allUninstalled = new HashSet<>(pm(s, "list", "packages", "-u"));
        Map<String, PkgState> r = new TreeMap<>();
        for(String pkg : installed) r.put(pkg, disabled.contains(pkg) ? PkgState.DISABLED : PkgState.ENABLED);
        for(String pkg : allUninstalled) if(!installed.contains(pkg)) r.put(pkg, PkgState.UNINSTALLED);
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
            while((l = br.readLine()) != null) if(l.startsWith("package:")) r.add(l.substring(8).trim());
        }
        return r;
    }
    
    void run(String s, String... a) throws Exception {
        List<String> c = new ArrayList<>(List.of(p));
        if(s != null && !s.isEmpty()) { c.add("-s"); c.add(s); }
        c.add("shell");
        c.addAll(Arrays.asList(a));
        if(new ProcessBuilder(c).start().waitFor() != 0) throw new IOException("Failed");
    }
}

interface AiClient {
    String ask(String pkg, String sum) throws Exception;
}

class Gemini implements AiClient {
    String k, m, pt; 
    HttpClient c = HttpClient.newHttpClient();
    Gemini(String k, String m, String pt) { this.k = k; this.m = m.isBlank() ? "gemini-flash-lite-latest" : m; this.pt = pt; }
    
    public String ask(String pkg, String sum) throws Exception {
        String pr = (pt != null ? pt : "Analyze code/app package: {package}. Summary: {summary}. Respond format: FLAG:[1-3] PERF:[1-3] PRIVACY:[1-3] Summary:[1-2 sentences]")
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

class Groq implements AiClient {
    String k, m, pt;
    HttpClient c = HttpClient.newHttpClient();
    Groq(String k, String m, String pt) { this.k = k; this.m = m.isBlank() ? "llama-3.3-70b-versatile" : m; this.pt = pt; }

    public String ask(String pkg, String sum) throws Exception {
        String pr = (pt != null ? pt : "Analyze code/app package: {package}. Summary: {summary}. Respond format: FLAG:[1-3] PERF:[1-3] PRIVACY:[1-3] Summary:[1-2 sentences]")
                    .replace("{package}", pkg).replace("{summary}", sum == null ? "" : sum);
        String b = "{\"model\":\"" + m + "\",\"messages\":[{\"role\":\"user\",\"content\":\"" + pr.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"}]}";

        HttpRequest r = HttpRequest.newBuilder()
                .uri(URI.create("https://api.groq.com/openai/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + k)
                .POST(HttpRequest.BodyPublishers.ofString(b))
                .timeout(Duration.ofSeconds(30)).build();

        HttpResponse<String> res = c.send(r, HttpResponse.BodyHandlers.ofString());
        if(res.statusCode() != 200) return "[ERR " + res.statusCode() + "]";

        Matcher mt = Pattern.compile("\"content\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"").matcher(res.body());
        return mt.find() ? mt.group(1).replace("\\n", "\n").replace("\\\"", "\"") : "[No Response]";
    }
}

class Frame extends JFrame {
    Adb adb = new Adb(); 
    Tools tools; 
    AiClient aiClient;
    JComboBox<String> dev = new JComboBox<>(), filt = new JComboBox<>(new String[]{"All", "Enabled", "Disabled", "Uninstalled"});
    JComboBox<String> cProvider = new JComboBox<>(new String[]{"Groq", "Gemini"});
    JTextField tMod = new JTextField("llama-3.3-70b-versatile"), tSearch = new JTextField();
    JPasswordField tKey = new JPasswordField(); 
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
        super("maybeUltimateAndroidDebloater"); 
        setSize(1100, 650); 
        setDefaultCloseOperation(EXIT_ON_CLOSE); 
        setLocationRelativeTo(null);
        
        tools = new Tools(this);
        if (tools.adb() != null) adb.set(tools.adb());

        cProvider.addActionListener(e -> {
            if ("Groq".equals(cProvider.getSelectedItem())) tMod.setText("llama-3.3-70b-versatile");
            else tMod.setText("gemini-flash-lite-latest");
        });
        
        JPanel pnlHeader = new JPanel(new GridBagLayout());
        pnlHeader.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(3, 4, 3, 4); 
        g.fill = GridBagConstraints.HORIZONTAL;

        g.gridy = 0; g.gridx = 0; g.weightx = 0; pnlHeader.add(new JLabel("Provider:"), g);
        g.gridx = 1; g.weightx = 0.1; pnlHeader.add(cProvider, g);
        g.gridx = 2; g.weightx = 0; pnlHeader.add(new JLabel("API Key:"), g);
        g.gridx = 3; g.weightx = 0.25; pnlHeader.add(tKey, g);
        g.gridx = 4; g.weightx = 0; pnlHeader.add(new JLabel("Model:"), g);
        g.gridx = 5; g.weightx = 0.2; pnlHeader.add(tMod, g);
        g.gridx = 6; g.weightx = 0; pnlHeader.add(new JLabel("Device:"), g);
        g.gridx = 7; g.weightx = 0.15; pnlHeader.add(dev, g);

        JPanel pnlDevBtns = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        JButton bRef = new JButton("Refresh"); bRef.addActionListener(e -> refresh());
        JButton bLoad = new JButton("Load Packages"); bLoad.addActionListener(e -> load());
        JButton bCheck = new JButton("Check Tools"); bCheck.addActionListener(e -> checkToolsStatus());
        pnlDevBtns.add(bRef); pnlDevBtns.add(bLoad); pnlDevBtns.add(bCheck);
        g.gridx = 8; g.weightx = 0; pnlHeader.add(pnlDevBtns, g);

        g.gridy = 1; g.gridx = 0; g.weightx = 0; pnlHeader.add(new JLabel("Filter:"), g);
        g.gridx = 1; g.weightx = 0.1; pnlHeader.add(filt, g); filt.addActionListener(e -> applyFilt());
        g.gridx = 2; g.weightx = 0; pnlHeader.add(new JLabel("Search:"), g);
        g.gridx = 3; g.gridwidth = 5; g.weightx = 0.6; pnlHeader.add(tSearch, g); g.gridwidth = 1;
        
        tSearch.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { applyFilt(); }
            public void removeUpdate(DocumentEvent e) { applyFilt(); }
            public void changedUpdate(DocumentEvent e) { applyFilt(); }
        });

        JPanel pnlSelBtns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        JButton bSel = new JButton("Select all"); bSel.addActionListener(e -> setAllSelected(true));
        JButton bDes = new JButton("Deselect all"); bDes.addActionListener(e -> setAllSelected(false));
        pnlSelBtns.add(bSel); pnlSelBtns.add(bDes);
        g.gridx = 8; g.weightx = 0; pnlHeader.add(pnlSelBtns, g);

        setLayout(new BorderLayout());
        add(pnlHeader, BorderLayout.NORTH);

        table.setRowHeight(24);
        table.getColumnModel().getColumn(0).setMaxWidth(40);
        table.getColumnModel().getColumn(4).setMaxWidth(80);
        table.getColumnModel().getColumn(4).setCellRenderer(new ButtonRenderer());
        table.getColumnModel().getColumn(4).setCellEditor(new ButtonEditor(new JCheckBox()));

        model.addTableModelListener(e -> {
            if (e.getColumn() == 0 && e.getFirstRow() >= 0) {
                int r = e.getFirstRow();
                Boolean checked = (Boolean) model.getValueAt(r, 0);
                String pkgName = (String) model.getValueAt(r, 1);
                pkgs.stream().filter(p -> p.n.equals(pkgName)).findFirst().ifPresent(p -> p.sel = checked);
            }
        });

        add(new JScrollPane(table), BorderLayout.CENTER);

        JPanel pnlSouth = new JPanel(new BorderLayout());
        JPanel pnlActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 5));
        
        for(String o : new String[]{"Disable", "Enable", "Uninstall", "Restore", "Export CSV", "Import CSV", "AI: Selected", "AI: All", "AI: Missing", "Edit Prompt"}) {
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
    
    void checkToolsStatus() {
        String sb = "<b>JDK:</b> " + System.getProperty("java.version") + "<br>" +
                    "<b>ADB:</b> " + (adb.ok() ? "OK" : "Missing") + "<br>" +
                    "<b>JADX:</b> " + (tools.jadx() != null ? "OK" : "Missing");
        JEditorPane ep = new JEditorPane("text/html", "<html><body style='padding:10px;'>" + sb + "</body></html>");
        ep.setEditable(false);
        JOptionPane.showMessageDialog(this, ep, "Tools Status", JOptionPane.INFORMATION_MESSAGE);
    }
    
    void setAllSelected(boolean val) {
        for(int i = 0; i < model.getRowCount(); i++) model.setValueAt(val, i, 0);
    }
    
    void showAi(int r) {
        int mr = table.convertRowIndexToModel(r);
        String txt = (String) model.getValueAt(mr, 3);
        if(txt != null && !txt.isEmpty()) {
            JTextArea ta = new JTextArea(15, 50);
            ta.setText(txt);
            ta.setWrapStyleWord(true);
            ta.setLineWrap(true);
            ta.setEditable(false);
            JOptionPane.showMessageDialog(this, new JScrollPane(ta), "AI Info", JOptionPane.INFORMATION_MESSAGE);
        } else JOptionPane.showMessageDialog(this, "No AI info.", "AI Info", JOptionPane.WARNING_MESSAGE);
    }
    
    void refresh() {
        stat.setText("Scanning...");
        new SwingWorker<List<String>, Void>() {
            protected List<String> doInBackground() throws Exception { return adb.devs(); }
            protected void done() {
                try {
                    dev.removeAllItems();
                    for(String s : get()) dev.addItem(s);
                    stat.setText(dev.getItemCount() + " devices");
                } catch(Exception e) { stat.setText("Scan error"); }
            }
        }.execute();
    }
    
    void load() {
        String s = (String) dev.getSelectedItem();
        if (s == null) return;
        stat.setText("Loading...");
        new SwingWorker<Map<String, PkgState>, Void>() {
            protected Map<String, PkgState> doInBackground() throws Exception { return adb.list(s); }
            protected void done() {
                try {
                    pkgs.clear();
                    get().forEach((k, v) -> pkgs.add(new Pkg(k, v)));
                    applyFilt();
                    stat.setText(pkgs.size() + " loaded");
                } catch(Exception e) { stat.setText("Load error"); }
            }
        }.execute();
    }
    
    void applyFilt() {
        model.setRowCount(0);
        String q = tSearch.getText().toLowerCase().trim();
        int fi = filt.getSelectedIndex();
        for(Pkg p : pkgs) {
            if((fi == 1 && p.s != PkgState.ENABLED) || (fi == 2 && p.s != PkgState.DISABLED) || (fi == 3 && p.s != PkgState.UNINSTALLED)) continue;
            if(!q.isEmpty() && !p.n.toLowerCase().contains(q)) continue;
            model.addRow(new Object[]{p.sel, p.n, p.s.toString(), p.ai, "Details"});
        }
    }
    
    void act(String op) {
        List<Pkg> sel = pkgs.stream().filter(p -> p.sel).collect(Collectors.toList());
        if(op.equals("Edit Prompt")) {
            prompt = JOptionPane.showInputDialog(this, "Prompt ({package}, {summary}):", prompt);
            return;
        }
        if(op.startsWith("AI")) {
            String k = new String(tKey.getPassword());
            if(k.isEmpty()) return;
            aiClient = "Groq".equals(cProvider.getSelectedItem()) ? new Groq(k, tMod.getText(), prompt) : new Gemini(k, tMod.getText(), prompt);
            List<Pkg> tgt = op.contains("All") ? pkgs : op.contains("Missing") ? 
                            pkgs.stream().filter(p -> p.ai.isEmpty() || p.ai.startsWith("[ERR")).collect(Collectors.toList()) : sel;
            if(tgt.isEmpty()) return;
            prog.setVisible(true); prog.setMaximum(tgt.size()); prog.setValue(0);
            new SwingWorker<Void, Integer>() {
                int i = 0;
                protected Void doInBackground() {
                    for(Pkg p : tgt) {
                        try {
                            p.ai = aiClient.ask(p.n, null);
                            // Se riceve un 429, aspetta 2 secondi e riprova una volta
                            if(p.ai.contains("429")) {
                                Thread.sleep(2000);
                                p.ai = aiClient.ask(p.n, null);
                            }
                            Thread.sleep(1000); // Pausa di 1s per prevenire il rate limit
                        } catch(Exception e) { p.ai = "[ERR]"; }
                        publish(++i);
                    }
                    return null;
                }
                protected void process(List<Integer> c) { prog.setValue(c.get(c.size() - 1)); applyFilt(); }
                protected void done() { prog.setVisible(false); stat.setText("AI Done"); }
            }.execute(); 
            return;
        }
        if (op.equals("Export CSV")) {
            JFileChooser fc = new JFileChooser();
            if (fc.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
                File f = fc.getSelectedFile();
                if (!f.getName().endsWith(".csv")) f = new File(f.getAbsolutePath() + ".csv");
                try (PrintWriter w = new PrintWriter(f)) {
                    w.println("package,state,ai");
                    for (Pkg x : pkgs) w.println(x.n + "," + x.s + ",\"" + x.ai.replace("\"", "\"\"") + "\"");
                } catch (Exception ignored) {}
            }
            return;
        }
        if (op.equals("Import CSV")) {
            JFileChooser fc = new JFileChooser();
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                try (BufferedReader br = new BufferedReader(new FileReader(fc.getSelectedFile()))) {
                    String line; boolean head = true; pkgs.clear();
                    while ((line = br.readLine()) != null) {
                        if (head) { head = false; continue; }
                        String[] p = line.split(",(?=(?:[^\"]*\"[^\"]*\")*$)", -1);
                        if (p.length >= 2) pkgs.add(new Pkg(p[0].trim(), PkgState.from(p[1].trim()), p.length > 2 ? p[2].replace("\"", "").trim() : ""));
                    }
                    applyFilt();
                } catch (Exception ignored) {}
            }
            return;
        }
        if(sel.isEmpty()) return;
        String s = (String) dev.getSelectedItem();
        new SwingWorker<Void, Void>() {
            protected Void doInBackground() {
                for(Pkg p : sel) {
                    try {
                        if(op.equals("Disable")) { adb.run(s, "pm", "disable-user", "--user", "0", p.n); p.s = PkgState.DISABLED; }
                        else if(op.equals("Enable")) { adb.run(s, "pm", "enable", p.n); p.s = PkgState.ENABLED; }
                        else if(op.equals("Uninstall")) { adb.run(s, "pm", "uninstall", "--user", "0", p.n); p.s = PkgState.UNINSTALLED; }
                        else if(op.equals("Restore")) { adb.run(s, "cmd", "package", "install-existing", p.n); p.s = PkgState.ENABLED; }
                    } catch(Exception ignored) {}
                }
                return null;
            }
            protected void done() { applyFilt(); stat.setText("Done: " + op); }
        }.execute();
    }

    class ButtonRenderer extends JButton implements TableCellRenderer {
        public ButtonRenderer() { setOpaque(true); }
        public Component getTableCellRendererComponent(JTable t, Object v, boolean s, boolean f, int r, int c) {
            setText((v == null) ? "Details" : v.toString());
            return this;
        }
    }

    class ButtonEditor extends DefaultCellEditor {
        private JButton b = new JButton();
        private String l;
        private boolean p;
        private int r;

        public ButtonEditor(JCheckBox cb) {
            super(cb);
            b.setOpaque(true);
            b.addActionListener(e -> fireEditingStopped());
        }

        public Component getTableCellEditorComponent(JTable t, Object v, boolean s, int r, int c) {
            l = (v == null) ? "Details" : v.toString();
            b.setText(l);
            this.r = r; p = true;
            return b;
        }

        public Object getCellEditorValue() {
            if (p) SwingUtilities.invokeLater(() -> showAi(r));
            p = false;
            return l;
        }
    }
}