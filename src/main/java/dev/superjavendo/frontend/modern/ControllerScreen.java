package dev.superjavendo.frontend.modern;

import dev.superjavendo.config.AppPaths;
import dev.superjavendo.input.ControllerAssignments;
import dev.superjavendo.input.GamepadDevices;
import dev.superjavendo.input.GamepadMonitor;
import dev.superjavendo.input.ProfileLibrary;
import dev.superjavendo.input.ProfileStore;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static dev.superjavendo.i18n.I18n.t;

/** Controller profiles: list on the left, pad drawing and assignments in the middle, the selected key and live test on the right. */
final class ControllerScreen extends JPanel {
    private static final int LISTEN_SECONDS = 5;

    private final Shell shell;
    private final ProfileLibrary library;
    private final ControllerAssignments assign;

    private List<ProfileLibrary.Info> infos = List.of();
    private ProfileEditor editor;
    private String selKey = "up";
    private int deviceChoice = 0;
    private boolean shortcutsTab;
    private boolean active;

    // live test / listening
    private GamepadMonitor monitor;
    private int monitorDevice = -2;
    private final Set<String> pressed = new LinkedHashSet<>();
    private String lastInput = "";
    private boolean listening;
    private int listenLeft;
    private String listenMsg = "";
    boolean listening() { return listenLeft > 0; }
    private final javax.swing.Timer listenTimer = new javax.swing.Timer(1000, e -> tickListen());
    private final KeyEventDispatcher keys = this::dispatch;

    private final JPanel list = new JPanel();
    private final Widgets.Search search = new Widgets.Search(t("ctl.search"));
    private final Widgets.Field nameField = new Widgets.Field("");
    private final Ui.Btn deviceBtn = new Ui.Btn("", Pal.CARD, Pal.TXT, Pal.CARD_LINE).font(Pal.ui(14, 1)).chevron();
    private final Ui.Btn p1 = new Ui.Btn(t("ctl.player", 1), Pal.CARD, Pal.TXT2, Pal.CARD_LINE);
    private final Ui.Btn p2 = new Ui.Btn(t("ctl.player", 2), Pal.CARD, Pal.TXT2, Pal.CARD_LINE);
    private final Ui.Btn tabButtons = new Ui.Btn(t("ctl.tab.buttons"), null, Pal.TXT, null).font(Pal.ui(15, 2));
    private final Ui.Btn tabShortcuts = new Ui.Btn(t("ctl.tab.shortcuts"), null, Pal.TXT2, null).font(Pal.ui(15, 2));
    private final CardLayout cards = new CardLayout();
    private final JPanel cardPanel = new JPanel(cards);
    private final PadCanvas pad = new PadCanvas();
    private final JPanel grid = new JPanel(new GridLayout(6, 2, 12, 10));
    private final JPanel shortcuts = new JPanel();
    private final JPanel details = new Widgets.Body();
    private final JPanel foot = new JPanel();
    private boolean updatingName;

    ControllerScreen(Shell shell, AppPaths paths) {
        super(new BorderLayout());
        this.shell = shell;
        ProfileStore store = new ProfileStore(paths.joystickDir());
        library = new ProfileLibrary(store);
        assign = new ControllerAssignments(paths.controllersFile());
        setBackground(Pal.MAIN);

        // ---- left
        JPanel left = new JPanel(new BorderLayout(0, 12));
        left.setBackground(Pal.SIDE);
        left.setBorder(new EmptyBorder(24, 20, 20, 20));
        JPanel top = new JPanel();
        top.setOpaque(false);
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        JLabel cap = section(t("ctl.profiles"));
        top.add(cap);
        search.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
        search.setAlignmentX(LEFT_ALIGNMENT);
        top.add(search);
        left.add(top, BorderLayout.NORTH);
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        list.setOpaque(false);
        left.add(Widgets.scroll(list, Pal.SIDE), BorderLayout.CENTER);
        JPanel btns = new JPanel(new GridLayout(1, 2, 8, 0));
        btns.setOpaque(false);
        btns.add(new Ui.Btn(t("ctl.new"), null, Pal.TXT2, Pal.CARD_LINE).onClick(this::newProfile));
        btns.add(new Ui.Btn(t("ctl.duplicate"), Pal.CARD, Pal.TXT, Pal.CARD_LINE).onClick(this::duplicateProfile));
        btns.setPreferredSize(new Dimension(0, 44));
        left.add(btns, BorderLayout.SOUTH);
        left.setPreferredSize(new Dimension(272, 0));
        add(edge(left, true), BorderLayout.WEST);

        // ---- centre
        JPanel centre = new JPanel();
        centre.setBackground(Pal.MAIN);
        centre.setLayout(new BoxLayout(centre, BoxLayout.Y_AXIS));
        centre.setBorder(new EmptyBorder(24, 28, 20, 24));
        JPanel head = new JPanel(new GridBagLayout());
        head.setOpaque(false);
        head.setAlignmentX(LEFT_ALIGNMENT);
        GridBagConstraints c = new GridBagConstraints();
        c.fill = GridBagConstraints.HORIZONTAL;
        c.gridy = 0; c.weightx = 1; c.insets = new Insets(0, 0, 4, 12);
        c.gridx = 0; head.add(small(t("ctl.name")), c);
        c.gridx = 1; head.add(small(t("ctl.device")), c);
        c.gridx = 2; c.weightx = 0; c.insets = new Insets(0, 0, 4, 0); head.add(small(t("ctl.usedon")), c);
        c.gridy = 1; c.weightx = 1; c.insets = new Insets(0, 0, 0, 12);
        c.gridx = 0; head.add(nameField, c);
        deviceBtn.setPreferredSize(new Dimension(200, 44));
        c.gridx = 1; head.add(deviceBtn, c);
        JPanel players = new JPanel(new GridLayout(1, 2, 6, 0));
        players.setOpaque(false);
        p1.setPreferredSize(new Dimension(96, 44));
        p2.setPreferredSize(new Dimension(96, 44));
        players.add(p1);
        players.add(p2);
        c.gridx = 2; c.weightx = 0; c.insets = new Insets(0, 0, 0, 0); head.add(players, c);
        head.setMaximumSize(new Dimension(Integer.MAX_VALUE, 80));
        centre.add(head);
        centre.add(Box.createVerticalStrut(14));
        JPanel tabs = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        tabs.setOpaque(false);
        tabs.setAlignmentX(LEFT_ALIGNMENT);
        tabs.add(tabButtons);
        tabs.add(tabShortcuts);
        tabs.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
        centre.add(tabs);
        centre.add(Box.createVerticalStrut(8));

        JPanel buttonsCard = new JPanel(new BorderLayout(0, 14));
        buttonsCard.setOpaque(false);
        pad.setPreferredSize(new Dimension(640, 270));
        buttonsCard.add(pad, BorderLayout.CENTER);
        grid.setOpaque(false);
        grid.setPreferredSize(new Dimension(640, 6 * 46 + 5 * 10));
        buttonsCard.add(grid, BorderLayout.SOUTH);
        shortcuts.setOpaque(false);
        shortcuts.setLayout(new BoxLayout(shortcuts, BoxLayout.Y_AXIS));
        cardPanel.setOpaque(false);
        cardPanel.add(buttonsCard, "buttons");
        JPanel scWrap = new JPanel(new BorderLayout());
        scWrap.setOpaque(false);
        scWrap.add(shortcuts, BorderLayout.NORTH);
        cardPanel.add(scWrap, "shortcuts");
        cardPanel.setAlignmentX(LEFT_ALIGNMENT);
        centre.add(cardPanel);
        add(Widgets.scroll(centre, Pal.MAIN), BorderLayout.CENTER);

        // ---- right
        details.setLayout(new BoxLayout(details, BoxLayout.Y_AXIS));
        details.setBackground(Pal.SIDE);
        details.setBorder(new EmptyBorder(24, 20, 12, 20));
        foot.setLayout(new BoxLayout(foot, BoxLayout.Y_AXIS));
        foot.setBackground(Pal.SIDE);
        foot.setBorder(new EmptyBorder(8, 20, 20, 20));
        JPanel right = new JPanel(new BorderLayout());
        right.setBackground(Pal.SIDE);
        right.add(Widgets.scroll(details, Pal.SIDE), BorderLayout.CENTER);
        right.add(foot, BorderLayout.SOUTH);
        JPanel rw = edge(right, false);
        rw.setPreferredSize(new Dimension(368, 0));
        add(rw, BorderLayout.EAST);

        // ---- wiring
        pad.onPick(k -> { selKey = k; rebuildSelection(); });
        tabButtons.onClick(() -> setTab(false));
        tabShortcuts.onClick(() -> setTab(true));
        search.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { rebuildList(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { rebuildList(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { rebuildList(); }
        });
        nameField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            void ch() { if (editor != null && !updatingName) { editor.setName(nameField.getText()); rebuildFoot(); } }
            public void insertUpdate(javax.swing.event.DocumentEvent e) { ch(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { ch(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { ch(); }
        });
        p1.onClick(() -> togglePlayer(1));
        p2.onClick(() -> togglePlayer(2));
        deviceBtn.onClick(this::pickDevice);
        listenTimer.setRepeats(true);

        reload(null);
    }

    private static JLabel section(String s) {
        JLabel l = new JLabel(s.toUpperCase());
        l.setFont(Pal.ui(11, 2).deriveFont(java.util.Map.of(java.awt.font.TextAttribute.TRACKING, 0.12f)));
        l.setForeground(Pal.TXT3);
        l.setBorder(new EmptyBorder(0, 4, 10, 0));
        l.setAlignmentX(LEFT_ALIGNMENT);
        return l;
    }

    private static JLabel small(String s) { return Widgets.label(s, Pal.ui(13, 0), Pal.TXT2); }

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

    // ---------------------------------------------------------------------------------------- activation

    /** Called by the shell when the tab is shown or hidden: the pad monitor and the key listener only run while visible. */
    void setActive(boolean on) {
        active = on;
        if (on) {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(keys);
            reload(editor == null ? null : editor.id());
        } else {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(keys);
            stopListening();
            stopMonitor();
        }
    }

    private void ensureMonitor() {
        int dev = active && editor != null && !editor.keyboard() ? deviceChoice : -1;
        if (dev == monitorDevice) return;
        stopMonitor();
        monitorDevice = dev;
        if (dev >= 0) monitor = new GamepadMonitor(dev, 16000, (token, down) -> SwingUtilities.invokeLater(() -> input(token, down)));
    }

    private void stopMonitor() {
        if (monitor != null) monitor.close();
        monitor = null;
        monitorDevice = -2;
        pressed.clear();
        pad.setLit(Set.of());
    }

    private boolean dispatch(KeyEvent e) {
        if (editor == null || !editor.keyboard() || !active) return false;
        if (e.getID() != KeyEvent.KEY_PRESSED && e.getID() != KeyEvent.KEY_RELEASED) return false;
        if (!SwingUtilities.isDescendingFrom(e.getComponent(), this) && !listening) return false;
        if (e.getComponent() instanceof javax.swing.text.JTextComponent && !listening) return false;
        String token = dev.superjavendo.input.ProfileFile.keyToken(e.getKeyCode());
        if (listening) {
            if (e.getID() != KeyEvent.KEY_PRESSED) return true;
            if (token == null) { listenMsg = t("ctl.keyunsupported"); rebuildSelection(); return true; }
            addToken(token);
            return true;
        }
        if (token != null) input(token, e.getID() == KeyEvent.KEY_PRESSED);
        return false;
    }

    /** A press or release on the device (event thread). */
    private void input(String token, boolean down) {
        if (editor == null) return;
        if (listening) { if (down) addToken(token); return; }
        if (down) pressed.add(token); else pressed.remove(token);
        Set<String> lit = new LinkedHashSet<>();
        for (String tk : pressed) for (String target : editor.targetsOf(tk)) if (ProfileEditor.BUTTONS.contains(target)) lit.add(target);
        pad.setLit(lit);
        if (down) {
            List<String> targets = editor.targetsOf(token);
            lastInput = ProfileEditor.pretty(token) + (targets.isEmpty() ? "" : " → " + String.join(", ", targets.stream().map(k -> label(k)).toList()));
            rebuildDetails();
        }
    }

    // ---------------------------------------------------------------------------------------- data

    private static String label(String key) { return t("key." + key); }

    /** Reads the profile list again and opens {@code keep} (or the first profile). */
    void reload(String keep) {
        try { infos = library.list(); }
        catch (IOException e) { infos = List.of(); }
        String want = keep != null ? keep : editor != null ? editor.id() : null;
        final String w = want;
        if (w == null || infos.stream().noneMatch(i -> i.id().equals(w))) want = infos.isEmpty() ? null : infos.get(0).id();
        open(want);
    }

    private void open(String id) {
        stopListening();
        editor = null;
        if (id != null) {
            try { editor = new ProfileEditor(library, id); }
            catch (IOException e) { JOptionPane.showMessageDialog(this, e.getMessage(), t("error"), JOptionPane.ERROR_MESSAGE); }
        }
        pressed.clear();
        lastInput = "";
        pad.setLit(Set.of());
        if (editor != null) {
            int assigned = assignedDevice(editor.id());
            if (assigned >= 0) deviceChoice = assigned;
            else if (!GamepadDevices.present().isEmpty() && !GamepadDevices.present().contains(deviceChoice)) deviceChoice = GamepadDevices.present().get(0);
        }
        rebuildAll();
    }

    private int assignedDevice(String id) {
        for (int p = 1; p <= ControllerAssignments.PLAYERS; p++)
            for (ControllerAssignments.Entry e : assign.of(p)) if (e.profile().equals(id) && e.device() >= 0) return e.device();
        return -1;
    }

    private ProfileLibrary.Info info(String id) { return infos.stream().filter(i -> i.id().equals(id)).findFirst().orElse(null); }

    private void select(String id) {
        if (editor != null && editor.id().equals(id)) return;
        if (!resolveDirty()) return;
        open(id);
    }

    /** Asks what to do with unsaved changes. @return false if the user cancelled */
    private boolean resolveDirty() {
        if (editor == null || !editor.dirty()) return true;
        Object[] opts = {t("ctl.save"), t("ctl.discard"), t("ctl.cancel")};
        int r = JOptionPane.showOptionDialog(this, t("ctl.dirtyq", editor.name()), t("ctl.unsaved"), JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, opts, opts[0]);
        if (r == 0) return saveProfile();
        if (r == 1) { try { editor.discard(); } catch (IOException ignored) { } return true; }
        return false;
    }

    private boolean saveProfile() {
        try {
            editor.save();
            infos = library.list();
            rebuildAll();
            shell.refreshPads();
            return true;
        } catch (IOException | IllegalArgumentException e) {
            JOptionPane.showMessageDialog(this, t("ctl.savefail") + "\n" + e.getMessage(), t("error"), JOptionPane.ERROR_MESSAGE);
            return false;
        }
    }

    private void newProfile() {
        if (!resolveDirty()) return;
        String name = JOptionPane.showInputDialog(this, t("ctl.newname"), t("ctl.new"), JOptionPane.QUESTION_MESSAGE);
        if (name == null || name.isBlank()) return;
        Object[] types = {t("ctl.type.gamepad"), t("ctl.type.keyboard")};
        int r = JOptionPane.showOptionDialog(this, t("ctl.newtype"), t("ctl.new"), JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, types, types[0]);
        if (r < 0) return;
        try { reload(library.create(name.trim(), r == 0 ? "gamepad" : "keyboard")); }
        catch (IOException e) { JOptionPane.showMessageDialog(this, e.getMessage(), t("error"), JOptionPane.ERROR_MESSAGE); }
    }

    private void duplicateProfile() {
        if (editor == null || !resolveDirty()) return;
        try { reload(library.duplicate(editor.id(), t("ctl.copyof", editor.name()))); }
        catch (IOException e) { JOptionPane.showMessageDialog(this, e.getMessage(), t("error"), JOptionPane.ERROR_MESSAGE); }
    }

    private void togglePlayer(int player) {
        if (editor == null) return;
        try {
            boolean on = assign.uses(player, editor.id());
            assign.set(player, editor.id(), !on, editor.keyboard() ? -1 : deviceChoice);
            shell.refreshPads();
        } catch (IOException e) { JOptionPane.showMessageDialog(this, e.getMessage(), t("error"), JOptionPane.ERROR_MESSAGE); }
        rebuildAll();
    }

    private void pickDevice() {
        if (editor == null || editor.keyboard()) return;
        JPopupMenu m = new JPopupMenu();
        m.setBackground(Pal.CARD);
        m.setBorder(BorderFactory.createLineBorder(Pal.CARD_LINE));
        List<Integer> present = GamepadDevices.present();
        List<Integer> all = new ArrayList<>(present);
        if (!all.contains(deviceChoice)) all.add(deviceChoice);
        java.util.Collections.sort(all);
        for (int n : all) {
            JMenuItem it = new JMenuItem(deviceText(n));
            it.setFont(Pal.ui(14, 0));
            it.setBackground(Pal.CARD);
            it.setForeground(Pal.TXT);
            it.addActionListener(e -> {
                deviceChoice = n;
                try { assign.setDevice(editor.id(), n); } catch (IOException ignored) { }
                pressed.clear();
                rebuildAll();
            });
            m.add(it);
        }
        m.show(deviceBtn, 0, deviceBtn.getHeight() + 4);
    }

    /** For the button: "js0 · Name", cut to fit, without the connection state (the menu has it). */
    private static String deviceShort(int n) {
        String name = GamepadDevices.name(n);
        String s = "js" + n + (name.isEmpty() ? "" : " · " + name);
        if (!GamepadDevices.connected(n)) s += " ✕";
        return s.length() > 22 ? s.substring(0, 21) + "…" : s;
    }

    private static String deviceText(int n) {
        String name = GamepadDevices.name(n);
        boolean on = GamepadDevices.connected(n);
        return "js" + n + (name.isEmpty() ? "" : " · " + name) + " (" + t(on ? "dev.connected" : "dev.notconnected") + ")";
    }

    // ---------------------------------------------------------------------------------------- listening

    private void startListening() {
        if (editor == null) return;
        if (!editor.keyboard() && !GamepadDevices.connected(deviceChoice)) { listenMsg = t("ctl.nopad", deviceChoice); rebuildDetails(); return; }
        listening = true;
        listenLeft = LISTEN_SECONDS;
        listenMsg = "";
        listenTimer.restart();
        rebuildDetails();
    }

    private void tickListen() {
        if (--listenLeft <= 0) stopListening();
        else rebuildDetails();
    }

    private void stopListening() {
        listenTimer.stop();
        if (listening) { listening = false; rebuildDetails(); }
    }

    private void addToken(String token) {
        listenTimer.stop();
        listening = false;
        editor.add(selKey, token);
        rebuildAll();
    }

    // ---------------------------------------------------------------------------------------- UI

    private void setTab(boolean sc) {
        shortcutsTab = sc;
        if (sc && !ProfileEditor.ACTIONS.contains(selKey)) selKey = "save_state";
        if (!sc && !ProfileEditor.BUTTONS.contains(selKey)) selKey = "up";
        rebuildAll();
    }

    private void rebuildAll() {
        rebuildList();
        rebuildHeader();
        rebuildGrid();
        rebuildShortcuts();
        rebuildSelection();
        ensureMonitor();
    }

    private void rebuildSelection() {
        pad.setSelected(selKey);
        rebuildGrid();
        rebuildShortcuts();
        rebuildDetails();
    }

    private void rebuildHeader() {
        boolean has = editor != null;
        updatingName = true;
        nameField.setText(has ? editor.name() : "");
        updatingName = false;
        nameField.setEnabled(has);
        if (has) {
            deviceBtn.setText(editor.keyboard() ? t("dev.keyboard") : deviceShort(deviceChoice));
            deviceBtn.setEnabled(!editor.keyboard());
            for (int pl = 1; pl <= 2; pl++) {
                boolean on = assign.uses(pl, editor.id());
                (pl == 1 ? p1 : p2).setColors(on ? Pal.ACCENT : Pal.CARD, on ? Color.WHITE : Pal.TXT2, on ? null : Pal.CARD_LINE);
            }
        }
        p1.setEnabled(has);
        p2.setEnabled(has);
        tabButtons.setColors(shortcutsTab ? null : Pal.ACTIVE, shortcutsTab ? Pal.TXT2 : Pal.TXT, null);
        tabShortcuts.setColors(shortcutsTab ? Pal.ACTIVE : null, shortcutsTab ? Pal.TXT : Pal.TXT2, null);
        cards.show(cardPanel, shortcutsTab ? "shortcuts" : "buttons");
    }

    private void rebuildList() {
        list.removeAll();
        String q = search.getText().trim().toLowerCase(Locale.ROOT);
        for (ProfileLibrary.Info i : infos) {
            if (!q.isEmpty() && !i.name().toLowerCase(Locale.ROOT).contains(q) && !i.id().contains(q)) continue;
            list.add(new ProfileRow(i));
        }
        list.add(Box.createVerticalGlue());
        list.revalidate();
        list.repaint();
    }

    /** Players and device of a profile: "Player 1 · js0 connected". */
    private String statusOf(ProfileLibrary.Info i) {
        List<String> parts = new ArrayList<>();
        List<String> pl = new ArrayList<>();
        for (int p = 1; p <= ControllerAssignments.PLAYERS; p++) if (assign.uses(p, i.id())) pl.add(String.valueOf(p));
        if (!pl.isEmpty()) parts.add(t("ctl.playerlist", String.join(", ", pl)));
        if (i.keyboard()) parts.add(t("dev.keyboard"));
        else {
            int d = assignedDevice(i.id());
            if (d >= 0) parts.add("js" + d + " " + t(GamepadDevices.connected(d) ? "dev.connected" : "dev.notconnected"));
            else parts.add(t("dev.notconnected"));
        }
        return String.join(" · ", parts);
    }

    private void rebuildGrid() {
        grid.removeAll();
        if (editor != null) for (String b : ProfileEditor.BUTTONS) grid.add(new AssignmentCell(b));
        grid.revalidate();
        grid.repaint();
    }

    private void rebuildShortcuts() {
        shortcuts.removeAll();
        if (editor != null) {
            shortcuts.add(new ShortcutRow(null, "sc.action", "sc.profile", "sc.keyboard"));
            shortcuts.add(new ShortcutRow("save_state", "sc.save", null, "F5"));
            shortcuts.add(new ShortcutRow("load_state", "sc.load", null, "F9"));
            shortcuts.add(new ShortcutRow("-", "sc.slots", null, "1–4 / Shift+1–4"));
            shortcuts.add(new ShortcutRow("-", "sc.pause", null, "P"));
            shortcuts.add(new ShortcutRow("-", "sc.filter", null, "F"));
            shortcuts.add(new ShortcutRow("-", "sc.close", null, "Esc"));
            shortcuts.add(Box.createVerticalStrut(12));
            JLabel n = Widgets.label("<html><body style='width:560px'>" + t("sc.note") + "</body></html>", Pal.ui(13, 0), Pal.TXT3);
            n.setAlignmentX(LEFT_ALIGNMENT);
            shortcuts.add(n);
        }
        shortcuts.revalidate();
        shortcuts.repaint();
    }

    // ---- right column

    private static final String W = "<html><body style='width:270px'>";

    private static JLabel text(String s, Font f, Color c) {
        JLabel l = Widgets.label(W + s.replace("&", "&amp;").replace("<", "&lt;") + "</body></html>", f, c);
        l.setAlignmentX(LEFT_ALIGNMENT);
        return l;
    }

    private void rebuildDetails() {
        details.removeAll();
        if (editor == null) {
            details.add(text(t("ctl.noprofile"), Pal.ui(14, 0), Pal.TXT2));
        } else {
            details.add(section(shortcutsTab ? t("ctl.action") : t("ctl.snesbutton")));
            JLabel title = Widgets.label(label(selKey).toUpperCase(Locale.ROOT), Pal.title(26), Pal.TXT);
            title.setAlignmentX(LEFT_ALIGNMENT);
            details.add(title);
            details.add(Box.createVerticalStrut(6));
            List<String> tokens = editor.tokens(selKey);
            details.add(text(t(tokens.size() == 1 ? "ctl.nassigned1" : "ctl.nassigned", tokens.size()), Pal.ui(14, 0), Pal.TXT2));
            details.add(Box.createVerticalStrut(14));
            for (String tk : tokens) { details.add(new TokenCard(tk)); details.add(Box.createVerticalStrut(8)); }
            details.add(Box.createVerticalStrut(6));
            if (listening) details.add(listeningBox());
            else {
                Ui.Btn add = new Ui.Btn(t("ctl.addinput"), new Color(0x1f2040), Pal.TXT, Pal.ACCENT).onClick(this::startListening);
                add.setPreferredSize(new Dimension(100, 52));
                add.setMaximumSize(new Dimension(Integer.MAX_VALUE, 52));
                add.setAlignmentX(LEFT_ALIGNMENT);
                details.add(add);
                if (!listenMsg.isEmpty()) { details.add(Box.createVerticalStrut(8)); details.add(text(listenMsg, Pal.ui(13, 0), Pal.WARN)); }
            }
            details.add(Box.createVerticalStrut(18));
            details.add(liveBox());
        }
        details.add(Box.createVerticalGlue());
        details.revalidate();
        details.repaint();
        rebuildFoot();
    }

    private JComponent listeningBox() {
        JPanel p = new JPanel();
        p.setOpaque(false);
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setAlignmentX(LEFT_ALIGNMENT);
        p.setBorder(new EmptyBorder(14, 14, 14, 14));
        JLabel l = Widgets.label(W.replace("270", "240") + t("ctl.listening", listenLeft) + "</body></html>", Pal.ui(14, 2), Pal.ACCENT_L);
        l.setAlignmentX(LEFT_ALIGNMENT);
        p.add(l);
        if (!listenMsg.isEmpty()) { p.add(Box.createVerticalStrut(6)); p.add(text(listenMsg, Pal.ui(13, 0), Pal.WARN)); }
        p.add(Box.createVerticalStrut(10));
        Ui.Btn cancel = new Ui.Btn(t("ctl.cancel"), Pal.CARD, Pal.TXT, Pal.CARD_LINE).onClick(this::stopListening);
        cancel.setPreferredSize(new Dimension(100, 40));
        cancel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        cancel.setAlignmentX(LEFT_ALIGNMENT);
        p.add(cancel);
        JPanel box = new JPanel(new BorderLayout()) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = Ui.g2(g);
                Ui.fillRound(g2, 0, 0, getWidth(), getHeight(), 14, new Color(0x1f2040), Pal.ACCENT);
                g2.dispose();
            }
        };
        box.setOpaque(false);
        box.setAlignmentX(LEFT_ALIGNMENT);
        box.add(p);
        box.setMaximumSize(new Dimension(Integer.MAX_VALUE, 180));
        return box;
    }

    private JComponent liveBox() {
        JPanel p = new JPanel();
        p.setOpaque(false);
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(new EmptyBorder(14, 16, 14, 16));
        JComponent cap = new JComponent() {
            @Override protected void paintComponent(Graphics g) { Graphics2D g2 = Ui.g2(g); Ui.label(g2, t("ctl.livetest"), 0, 12); g2.dispose(); }
        };
        cap.setPreferredSize(new Dimension(100, 20));
        cap.setMaximumSize(new Dimension(Integer.MAX_VALUE, 20));
        cap.setAlignmentX(LEFT_ALIGNMENT);
        p.add(cap);
        JLabel hint = Widgets.label(W.replace("270", "222") + t(editor.keyboard() ? "ctl.livehintkb" : "ctl.livehint") + "</body></html>", Pal.ui(14, 0), Pal.TXT);
        hint.setAlignmentX(LEFT_ALIGNMENT);
        p.add(hint);
        p.add(Box.createVerticalStrut(8));
        String last = lastInput.isEmpty() ? "—" : lastInput;
        JLabel l = Widgets.label(W.replace("270", "222") + t("ctl.lastinput") + " <font face='monospaced' color='#e6e9ef'>" + last.replace("&", "&amp;").replace("<", "&lt;") + "</font></body></html>", Pal.ui(13, 0), Pal.TXT3);
        l.setAlignmentX(LEFT_ALIGNMENT);
        p.add(l);
        JPanel box = new JPanel(new BorderLayout()) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = Ui.g2(g);
                Ui.fillRound(g2, 0, 0, getWidth(), getHeight(), 14, Pal.CARD, null);
                g2.dispose();
            }
        };
        box.setOpaque(false);
        box.setAlignmentX(LEFT_ALIGNMENT);
        box.add(p);
        box.setMaximumSize(new Dimension(Integer.MAX_VALUE, 230));
        return box;
    }

    private void rebuildFoot() {
        foot.removeAll();
        if (editor != null) {
            boolean dirty = editor.dirty();
            if (dirty) {
                JLabel w = Widgets.label(t("ctl.unsaved"), Pal.ui(13, 1), Pal.WARN);
                w.setAlignmentX(LEFT_ALIGNMENT);
                foot.add(w);
                foot.add(Box.createVerticalStrut(8));
            }
            Ui.Btn save = new Ui.Btn(t("ctl.saveprofile"), Pal.ACCENT, Color.WHITE, null).onClick(this::saveProfile);
            save.setPreferredSize(new Dimension(100, 48));
            save.setMaximumSize(new Dimension(Integer.MAX_VALUE, 48));
            save.setAlignmentX(LEFT_ALIGNMENT);
            save.setEnabled(dirty);
            foot.add(save);
            foot.add(Box.createVerticalStrut(8));
            JPanel row = new JPanel(new GridLayout(1, 2, 8, 0));
            row.setOpaque(false);
            row.setAlignmentX(LEFT_ALIGNMENT);
            Ui.Btn discard = new Ui.Btn(t("ctl.discard"), Pal.CARD, Pal.TXT, Pal.CARD_LINE).onClick(() -> { try { editor.discard(); } catch (IOException ignored) { } rebuildAll(); });
            discard.setEnabled(dirty);
            Ui.Btn defaults = new Ui.Btn(t("ctl.defaults"), Pal.CARD, Pal.TXT, Pal.CARD_LINE).onClick(() -> {
                if (JOptionPane.showConfirmDialog(this, t("ctl.defaultsq"), t("ctl.defaults"), JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE) == JOptionPane.OK_OPTION) { editor.defaults(); rebuildAll(); }
            });
            row.add(discard);
            row.add(defaults);
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
            foot.add(row);
        }
        foot.revalidate();
        foot.repaint();
    }

    // ---------------------------------------------------------------------------------------- components

    private final class ProfileRow extends JComponent {
        private final ProfileLibrary.Info info;
        private boolean hover;

        ProfileRow(ProfileLibrary.Info info) {
            this.info = info;
            setPreferredSize(new Dimension(100, 62));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 62));
            setAlignmentX(LEFT_ALIGNMENT);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) { hover = false; repaint(); }
                @Override public void mousePressed(MouseEvent e) { select(info.id()); }
            });
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = Ui.g2(g);
            boolean sel = editor != null && editor.id().equals(info.id());
            if (sel) Ui.fillRound(g2, 0, 2, getWidth(), getHeight() - 4, 16, Pal.ACTIVE, null);
            else if (hover) Ui.fillRound(g2, 0, 2, getWidth(), getHeight() - 4, 16, Pal.ROW_SEL, null);
            String name = sel && editor.dirty() ? editor.name() + " •" : info.name();
            Ui.text(g2, Ui.ellipsize(g2.getFontMetrics(Pal.ui(15, 2)), name, getWidth() - 28), 14, 26, Pal.ui(15, 2), Pal.TXT);
            Ui.text(g2, Ui.ellipsize(g2.getFontMetrics(Pal.ui(12, 0)), statusOf(info), getWidth() - 28), 14, 46, Pal.ui(12, 0), Pal.TXT3);
            g2.dispose();
        }
    }

    /** One SNES button in the grid: its name and the inputs assigned to it. */
    private final class AssignmentCell extends JComponent {
        private final String key;

        AssignmentCell(String key) {
            this.key = key;
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() { @Override public void mousePressed(MouseEvent e) { selKey = key; rebuildSelection(); } });
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = Ui.g2(g);
            boolean sel = key.equals(selKey);
            Ui.fillRound(g2, 0, 0, getWidth(), getHeight(), 14, sel ? new Color(0x1f2040) : Pal.SIDE, sel ? Pal.ACCENT : Pal.CARD_LINE);
            int base = getHeight() / 2 + 5;
            Ui.text(g2, label(key).toUpperCase(Locale.ROOT), 16, base, Pal.ui(14, 2), Pal.TXT);
            List<String> tokens = editor.tokens(key);
            int x = 120;
            if (tokens.isEmpty()) Ui.text(g2, t("ctl.unassigned"), x, base, Pal.ui(13, 0), Pal.WARN);
            for (String tk : tokens) {
                String s = ProfileEditor.pretty(tk);
                FontMetrics fm = g2.getFontMetrics(Pal.mono(12));
                int w = fm.stringWidth(s) + 18;
                if (x + w > getWidth() - 8) break;
                Ui.fillRound(g2, x, getHeight() / 2 - 12, w, 24, 8, new Color(0x2a2f3d), null);
                Ui.text(g2, s, x + 9, base - 1, Pal.mono(12), Pal.TXT);
                x += w + 8;
            }
            g2.dispose();
        }
    }

    /** One input of the selected key, with the button that removes it. */
    private final class TokenCard extends JComponent {
        private final String token;

        TokenCard(String token) {
            this.token = token;
            setPreferredSize(new Dimension(100, 60));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 60));
            setAlignmentX(LEFT_ALIGNMENT);
            addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    if (e.getX() > getWidth() - 44) { editor.remove(selKey, token); rebuildAll(); }
                }
            });
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = Ui.g2(g);
            Ui.fillRound(g2, 0, 0, getWidth(), getHeight(), 12, Pal.CARD, Pal.CARD_LINE);
            Ui.text(g2, ProfileEditor.pretty(token), 16, 26, Pal.mono(15), Pal.TXT);
            Ui.text(g2, t(ProfileEditor.kindKey(token)), 16, 46, Pal.ui(13, 0), Pal.TXT2);
            g2.setColor(Pal.TXT2);
            g2.setStroke(new BasicStroke(1.6f));
            int cx = getWidth() - 24, cy = getHeight() / 2;
            g2.drawLine(cx - 5, cy - 5, cx + 5, cy + 5);
            g2.drawLine(cx - 5, cy + 5, cx + 5, cy - 5);
            g2.dispose();
        }
    }

    /** A row of the shortcuts table. {@code key} null = header, "-" = fixed row without a profile input. */
    private final class ShortcutRow extends JComponent {
        private final String key, action, profileCol, keyboard;

        ShortcutRow(String key, String action, String profileCol, String keyboard) {
            this.key = key; this.action = action; this.profileCol = profileCol; this.keyboard = keyboard;
            setPreferredSize(new Dimension(100, key == null ? 34 : 48));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, key == null ? 34 : 48));
            setAlignmentX(LEFT_ALIGNMENT);
            if (key != null && !key.equals("-")) {
                setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                addMouseListener(new MouseAdapter() { @Override public void mousePressed(MouseEvent e) { selKey = key; rebuildSelection(); } });
            }
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = Ui.g2(g);
            int w = getWidth(), c2 = w * 42 / 100, c3 = w * 76 / 100;
            if (key == null) {
                Ui.label(g2, t(action), 12, 22);
                Ui.label(g2, t(profileCol), c2, 22);
                Ui.label(g2, t(keyboard), c3, 22);
                g2.setColor(Pal.SEP);
                g2.drawLine(0, getHeight() - 1, w, getHeight() - 1);
            } else {
                boolean sel = key.equals(selKey);
                if (sel) Ui.fillRound(g2, 0, 2, w, getHeight() - 4, 12, new Color(0x1f2040), Pal.ACCENT);
                int base = getHeight() / 2 + 5;
                Ui.text(g2, t(action), 12, base, Pal.ui(14, 1), Pal.TXT);
                if (key.equals("-")) Ui.text(g2, "—", c2, base, Pal.ui(14, 0), Pal.TXT3);
                else {
                    List<String> tokens = editor.tokens(key);
                    int x = c2;
                    if (tokens.isEmpty()) Ui.text(g2, t("ctl.unassigned"), x, base, Pal.ui(13, 0), Pal.WARN);
                    for (String tk : tokens) {
                        String s = ProfileEditor.pretty(tk);
                        int cw = g2.getFontMetrics(Pal.mono(12)).stringWidth(s) + 18;
                        if (x + cw > c3 - 8) break;
                        Ui.fillRound(g2, x, getHeight() / 2 - 12, cw, 24, 8, new Color(0x2a2f3d), null);
                        Ui.text(g2, s, x + 9, base - 1, Pal.mono(12), Pal.TXT);
                        x += cw + 8;
                    }
                }
                Ui.text(g2, keyboard, c3, base, Pal.mono(13), Pal.TXT2);
                if (!sel) { g2.setColor(Pal.SEP); g2.drawLine(0, getHeight() - 1, w, getHeight() - 1); }
            }
            g2.dispose();
        }
    }
}
