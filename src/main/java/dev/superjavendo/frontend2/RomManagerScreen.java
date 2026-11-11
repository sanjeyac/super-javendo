package dev.superjavendo.frontend2;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Finds and fixes the problem files: ROM folders on the left, counters + table in the middle, group detail on the right. */
final class RomManagerScreen extends JPanel {
    private final Shell shell;
    private final LibraryData data;
    private final Covers covers;

    private final JPanel folders = new JPanel();
    private final JLabel metaInfo = Widgets.label("", Pal.ui(12, 0), Pal.TXT3);
    private final Ui.Btn scanBtn = new Ui.Btn("Scansiona di nuovo", Pal.ACCENT, Color.WHITE, null);
    private final Ui.Btn metaBtn = new Ui.Btn("Aggiorna metadati", Pal.CARD, Pal.TXT, Pal.CARD_LINE);
    private final Counters counters = new Counters();
    private final JPanel tabRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
    private final Map<RomCatalog.Filter, Ui.Btn> tabs = new EnumMap<>(RomCatalog.Filter.class);
    private final Widgets.Search search = new Widgets.Search("Nome file, titolo o CRC…");
    private final RomTable table = new RomTable();
    private final Ui.Btn renameBtn = new Ui.Btn("Rinomina secondo lo standard", Pal.CARD, Pal.TXT, Pal.CARD_LINE);
    private final Ui.Btn hideBtn = new Ui.Btn("Nascondi dalla libreria", Pal.CARD, Pal.TXT, Pal.CARD_LINE);
    private final Ui.Btn coverBtn = new Ui.Btn("Cerca copertine", Pal.CARD, Pal.TXT, Pal.CARD_LINE);
    private final RomDetail detail;
    private RomCatalog.Filter filter = RomCatalog.Filter.TO_FIX;
    private boolean scanning;

    RomManagerScreen(Shell shell, LibraryData data, Covers covers) {
        super(new BorderLayout());
        this.shell = shell;
        this.data = data;
        this.covers = covers;
        setBackground(Pal.MAIN);
        detail = new RomDetail(covers, data.paths, new RomDetail.Actions() {
            @Override public void setMain(Game g) { data.setPrimary(g); shell.dataChanged(); }
            @Override public void hide(List<Game> games, boolean hide) { hideFiles(games, hide); }
            @Override public void trash(Game g) { trashFile(g); }
            @Override public void changeCover(Game g) { pickCover(g); }
        });

        // ---- left
        JPanel left = new JPanel(new BorderLayout());
        left.setBackground(Pal.SIDE);
        left.setBorder(new EmptyBorder(24, 20, 20, 20));
        folders.setLayout(new BoxLayout(folders, BoxLayout.Y_AXIS));
        folders.setOpaque(false);
        left.add(Widgets.scroll(folders, Pal.SIDE), BorderLayout.CENTER);
        JPanel bottom = new JPanel();
        bottom.setOpaque(false);
        bottom.setLayout(new BoxLayout(bottom, BoxLayout.Y_AXIS));
        for (Ui.Btn b : new Ui.Btn[]{scanBtn, metaBtn}) { b.setPreferredSize(new Dimension(100, 46)); b.setMaximumSize(new Dimension(Integer.MAX_VALUE, 46)); b.setAlignmentX(LEFT_ALIGNMENT); }
        metaInfo.setAlignmentX(LEFT_ALIGNMENT);
        bottom.add(scanBtn);
        bottom.add(Box.createVerticalStrut(8));
        bottom.add(metaBtn);
        bottom.add(Box.createVerticalStrut(10));
        bottom.add(metaInfo);
        left.add(bottom, BorderLayout.SOUTH);
        left.setPreferredSize(new Dimension(272, 0));
        add(edge(left, true), BorderLayout.WEST);

        // ---- centre
        JPanel centre = new JPanel(new BorderLayout(0, 12));
        centre.setBackground(Pal.MAIN);
        centre.setBorder(new EmptyBorder(24, 28, 16, 20));
        JPanel top = new JPanel();
        top.setOpaque(false);
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        counters.setAlignmentX(LEFT_ALIGNMENT);
        top.add(counters);
        top.add(Box.createVerticalStrut(14));
        tabRow.setOpaque(false);
        tabRow.setAlignmentX(LEFT_ALIGNMENT);
        for (RomCatalog.Filter f : RomCatalog.Filter.values()) {
            Ui.Btn b = new Ui.Btn(f.label, null, Pal.TXT2, null).font(Pal.ui(14, 1)).onClick(() -> { filter = f; refresh(); });
            tabs.put(f, b);
            tabRow.add(b);
        }
        top.add(tabRow);
        top.add(Box.createVerticalStrut(12));
        search.setAlignmentX(LEFT_ALIGNMENT);
        search.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
        top.add(search);
        centre.add(top, BorderLayout.NORTH);
        JScrollPane ts = Widgets.scroll(table, Pal.MAIN);
        centre.add(ts, BorderLayout.CENTER);
        JPanel sel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        sel.setOpaque(false);
        sel.add(Widgets.label("Selezione multipla:", Pal.ui(13, 0), Pal.TXT2));
        for (Ui.Btn b : new Ui.Btn[]{renameBtn, hideBtn, coverBtn}) { b.setPreferredSize(new Dimension(b.getPreferredSize().width, 38)); sel.add(b); }
        centre.add(sel, BorderLayout.SOUTH);
        add(centre, BorderLayout.CENTER);

        JPanel rightWrap = edge(detail, false);
        rightWrap.setPreferredSize(new Dimension(368, 0));
        add(rightWrap, BorderLayout.EAST);

        // ---- wiring
        search.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            void ch() { refresh(); }
            public void insertUpdate(javax.swing.event.DocumentEvent e) { ch(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { ch(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { ch(); }
        });
        table.onSelection(this::selectionChanged);
        scanBtn.onClick(this::rescan);
        metaBtn.onClick(() -> { shell.dataChanged(); });
        renameBtn.onClick(() -> JOptionPane.showMessageDialog(this,
                "La rinomina automatica arriverà con la milestone M4: i save state e la SRAM sono legati al nome del file,\nquindi va fatta spostandoli insieme alla ROM.",
                "Rinomina secondo lo standard", JOptionPane.INFORMATION_MESSAGE));
        hideBtn.onClick(() -> {
            List<RomCatalog.Row> s = table.selected();
            if (s.isEmpty()) return;
            boolean allHidden = s.stream().allMatch(RomCatalog.Row::hidden);
            hideFiles(s.stream().map(RomCatalog.Row::game).toList(), !allHidden);
        });
        coverBtn.onClick(this::findCovers);
        refresh();
    }

    private static JPanel edge(JComponent c, boolean right) {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(Pal.SIDE);
        p.add(c, BorderLayout.CENTER);
        JPanel line = new JPanel();
        line.setBackground(Pal.SEP);
        line.setPreferredSize(new Dimension(1, 0));
        p.add(line, right ? BorderLayout.EAST : BorderLayout.WEST);
        return p;
    }

    // ---------------------------------------------------------------------------------------- refresh

    void refresh() {
        RomCatalog cat = data.catalog();
        counters.set(cat);
        for (var e : tabs.entrySet()) {
            RomCatalog.Filter f = e.getKey();
            boolean on = f == filter;
            e.getValue().setText(f == RomCatalog.Filter.TO_FIX ? f.label + " · " + cat.count(f) : f.label);
            e.getValue().setColors(on ? Pal.ACTIVE : null, on ? Pal.TXT : Pal.TXT2, null);
            e.getValue().setPreferredSize(new Dimension(e.getValue().getPreferredSize().width, 36));
        }
        tabRow.revalidate();
        table.setRows(cat.view(filter, search.getText()), cat.metadataLoaded());
        rebuildFolders();
        int lines = data.metadata().size();
        metaInfo.setText(lines > 0 ? "Metadati: games.tsv · " + lines + " righe" : "Nessun games.tsv (opzionale)");
        scanBtn.setText(scanning ? "Scansione in corso…" : "Scansiona di nuovo");
        scanBtn.setEnabled(!scanning);
    }

    /** Opens the screen on one file: widens the filter if it is not in the current rows. */
    void select(Path rom) {
        filter = RomCatalog.Filter.ALL;
        search.setText("");
        refresh();
        table.select(rom);
    }

    private void selectionChanged(List<RomCatalog.Row> s) {
        detail.show(s, data.catalog());
        boolean any = !s.isEmpty();
        renameBtn.setEnabled(any);
        hideBtn.setEnabled(any);
        coverBtn.setEnabled(any);
        hideBtn.setText(any && s.stream().allMatch(RomCatalog.Row::hidden) ? "Mostra nella libreria" : "Nascondi dalla libreria");
    }

    // ---------------------------------------------------------------------------------------- actions

    private void rescan() {
        if (scanning) return;
        scanning = true;
        refresh();
        shell.rescanAsync(added -> {
            scanning = false;
            refresh();
            if (added > 0) JOptionPane.showMessageDialog(this, added + (added == 1 ? " nuovo gioco trovato." : " nuovi giochi trovati."), "Scansione", JOptionPane.INFORMATION_MESSAGE);
        });
    }

    private void addFolder() {
        JFileChooser fc = new JFileChooser();
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        fc.setDialogTitle("Cartella con le ROM");
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        data.addFolder(fc.getSelectedFile().toPath());
        rescan();
    }

    private void removeFolder(Path dir) {
        int r = JOptionPane.showConfirmDialog(this, "Togliere la cartella dalla libreria?\nI giochi al suo interno escono dall'elenco; i file restano dove sono.\n\n" + dir,
                "Rimuovi cartella", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (r != JOptionPane.OK_OPTION) return;
        data.removeFolder(dir);
        shell.dataChanged();
    }

    private void hideFiles(List<Game> games, boolean hide) {
        data.setHidden(games.stream().map(Game::path).toList(), hide);
        shell.dataChanged();
    }

    private void trashFile(Game g) {
        int r = JOptionPane.showConfirmDialog(this, "Spostare nel cestino questo file?\n\n" + g.path() + "\n\nI save state restano nella cartella di configurazione.",
                "Sposta nel cestino", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (r != JOptionPane.YES_OPTION) return;
        try {
            data.trash(g);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Impossibile spostare il file:\n" + ex.getMessage(), "Errore", JOptionPane.ERROR_MESSAGE);
        }
        shell.dataChanged();
    }

    private void pickCover(Game g) {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Copertina di " + g.title());
        fc.setFileFilter(new FileNameExtensionFilter("Immagini (png, jpg)", "png", "jpg", "jpeg"));
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            covers.assign(g.path(), fc.getSelectedFile().toPath());
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Impossibile copiare l'immagine:\n" + ex.getMessage(), "Errore", JOptionPane.ERROR_MESSAGE);
        }
        shell.dataChanged();
    }

    /** Reads the cover folders again and reports how many of the selected files gained a cover. */
    private void findCovers() {
        List<RomCatalog.Row> s = table.selected();
        covers.reset();
        int found = 0;
        for (RomCatalog.Row r : s) if (!r.hasCover() && covers.exists(r.game().path())) found++;
        shell.dataChanged();
        JOptionPane.showMessageDialog(this, found + (found == 1 ? " copertina trovata" : " copertine trovate") + " tra i " + s.size() + " file selezionati.\n\n"
                + "Le copertine si cercano per nome del file (o titolo normalizzato) in:\n• " + data.paths.coversDir() + "\n• la cartella covers/ accanto alla ROM o alla sua cartella.",
                "Cerca copertine", JOptionPane.INFORMATION_MESSAGE);
    }

    // ---------------------------------------------------------------------------------------- folders column

    private void rebuildFolders() {
        folders.removeAll();
        JComponent cap = new JComponent() {
            @Override protected void paintComponent(Graphics g) { Graphics2D g2 = Ui.g2(g); Ui.label(g2, "Cartelle", 4, 14); g2.dispose(); }
        };
        cap.setPreferredSize(new Dimension(100, 28));
        cap.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        folders.add(cap);
        List<Path> roms = data.gameList.games().stream().map(e -> e.path()).toList();
        for (Sources.Folder f : data.sources.folders()) {
            int n = Sources.countUnder(f.path(), roms);
            folders.add(new FolderRow(f.path(), n + (n == 1 ? " file" : " file") + " · scansione " + Ui.shortDate(f.lastScan()), true));
        }
        int imgs = covers.ownCount();
        folders.add(new FolderRow(data.paths.coversDir(), "Copertine · " + imgs + (imgs == 1 ? " immagine" : " immagini"), false));
        folders.add(Box.createVerticalStrut(10));
        Ui.Btn add = new Ui.Btn("+ Aggiungi cartella", null, Pal.TXT2, Pal.CARD_LINE).onClick(this::addFolder).font(Pal.ui(14, 1));
        add.setPreferredSize(new Dimension(100, 44));
        add.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
        add.setAlignmentX(LEFT_ALIGNMENT);
        folders.add(add);
        folders.revalidate();
        folders.repaint();
    }

    private static String pretty(Path p) {
        String s = p.toString(), home = System.getProperty("user.home");
        return home != null && s.startsWith(home) ? "~" + s.substring(home.length()) : s;
    }

    private final class FolderRow extends JComponent {
        private final Path path;
        private final String sub;
        private final boolean removable;
        private boolean hover;

        FolderRow(Path path, String sub, boolean removable) {
            this.path = path; this.sub = sub; this.removable = removable;
            setPreferredSize(new Dimension(100, 62));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 62));
            setAlignmentX(LEFT_ALIGNMENT);
            setToolTipText(path.toString());
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) { hover = false; repaint(); }
                @Override public void mousePressed(MouseEvent e) { if (removable && e.getX() > getWidth() - 36) removeFolder(path); }
            });
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = Ui.g2(g);
            if (hover) Ui.fillRound(g2, 0, 2, getWidth(), getHeight() - 4, 16, Pal.ROW_SEL, null);
            FontMetrics mm = g2.getFontMetrics(Pal.mono(13));
            Ui.text(g2, Ui.ellipsize(mm, pretty(path), getWidth() - 52), 14, 26, Pal.mono(13), Pal.TXT);
            Ui.text(g2, Ui.ellipsize(g2.getFontMetrics(Pal.ui(12, 0)), sub, getWidth() - 30), 14, 46, Pal.ui(12, 0), Pal.TXT3);
            if (removable && hover) {
                g2.setColor(Pal.TXT2);
                g2.setStroke(new BasicStroke(1.6f));
                int cx = getWidth() - 20, cy = getHeight() / 2;
                g2.drawLine(cx - 4, cy - 4, cx + 4, cy + 4);
                g2.drawLine(cx - 4, cy + 4, cx + 4, cy - 4);
            }
            g2.dispose();
        }
    }

    // ---------------------------------------------------------------------------------------- counters

    private static final class Counters extends JComponent {
        private String[] values = new String[5];
        private static final String[] LABELS = {"ROM totali", "Riconosciute", "Senza copertina", "Duplicati / versioni", "Errori di lettura"};
        private static final Color[] COLORS = {Pal.TXT, Pal.OK, Pal.WARN, Pal.DUP, Pal.ERR};

        Counters() {
            setPreferredSize(new Dimension(600, 88));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 88));
            java.util.Arrays.fill(values, "—");
        }

        void set(RomCatalog cat) {
            var c = cat.counters();
            values = new String[]{String.valueOf(c.total()), cat.metadataLoaded() ? String.valueOf(c.recognized()) : "—",
                    String.valueOf(c.noCover()), String.valueOf(c.duplicates()), String.valueOf(c.errors())};
            repaint();
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = Ui.g2(g);
            int gap = 12, w = (getWidth() - gap * 4) / 5;
            for (int i = 0; i < 5; i++) {
                int x = i * (w + gap);
                Ui.fillRound(g2, x, 0, w, getHeight(), 16, Pal.CARD, Pal.CARD_LINE);
                Ui.text(g2, values[i], x + 18, 44, Pal.title(30), COLORS[i]);
                Ui.text(g2, Ui.ellipsize(g2.getFontMetrics(Pal.ui(13, 0)), LABELS[i], w - 24), x + 18, 68, Pal.ui(13, 0), Pal.TXT2);
            }
            g2.dispose();
        }
    }
}
