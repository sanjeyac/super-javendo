package dev.superjavendo.frontend.legacy;

import dev.superjavendo.config.AppPaths;
import dev.superjavendo.input.GamepadCapture;
import dev.superjavendo.input.ProfileFile;
import dev.superjavendo.input.ProfileStore;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Profile editor: pick a profile, click a control on the pad image, see its bindings below,
 * add the next key / pad input pressed or remove one, then save to the profile's .cfg.
 */
public final class ControllerConfigWindow extends JFrame {
    private final ProfileStore store = new ProfileStore(AppPaths.forCurrentUser().joystickDir());
    private final JComboBox<String> profiles = new JComboBox<>();
    private final JSpinner device = new JSpinner(new SpinnerNumberModel(0, 0, 15, 1));
    private final DefaultListModel<String> bindingModel = new DefaultListModel<>();
    private final JList<String> bindingList = new JList<>(bindingModel);
    private final JLabel header = new JLabel(" "), status = new JLabel(" ");
    private final PadView pad = new PadView(b -> showBindings());
    private final JButton add = new JButton("Aggiungi…"), remove = new JButton("Rimuovi"), save = new JButton("Salva");
    private ProfileFile file;
    private boolean dirty, capturing;

    public ControllerConfigWindow() {
        super("Configura controller");
        getContentPane().setBackground(Theme.BG);
        setLayout(new BorderLayout(8, 8));
        ((JComponent) getContentPane()).setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        top.setOpaque(false);
        top.add(label("Profilo:"));
        top.add(profiles);
        top.add(label("Joystick js"));
        top.add(device);
        add(top, BorderLayout.NORTH);
        add(pad, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout(6, 6));
        bottom.setOpaque(false);
        header.setForeground(Theme.INK);
        header.setFont(Theme.TITLE);
        bottom.add(header, BorderLayout.NORTH);
        bindingList.setFont(Theme.TEXT);
        bindingList.setBackground(Theme.PANEL);
        bindingList.setForeground(Theme.INK);
        bindingList.setVisibleRowCount(4);
        bottom.add(new JScrollPane(bindingList), BorderLayout.CENTER);
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        row.setOpaque(false);
        row.add(add); row.add(remove); row.add(save);
        status.setForeground(Theme.INK);
        JPanel south = new JPanel(new BorderLayout());
        south.setOpaque(false);
        south.add(row, BorderLayout.NORTH);
        south.add(status, BorderLayout.SOUTH);
        bottom.add(south, BorderLayout.SOUTH);
        add(bottom, BorderLayout.SOUTH);

        add.addActionListener(e -> capture());
        remove.addActionListener(e -> removeSelected());
        save.addActionListener(e -> save());
        profiles.addActionListener(e -> loadProfile());

        try { store.installDefaults(); } catch (IOException e) { status.setText("Cartella profili non scrivibile: " + e.getMessage()); }
        try (Stream<Path> s = Files.list(store.dir())) {
            s.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".cfg")).map(n -> n.substring(0, n.length() - 4))
                    .sorted().forEach(profiles::addItem);
        } catch (IOException e) { status.setText("Impossibile leggere " + store.dir()); }
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        loadProfile();
        pack();
        setLocationRelativeTo(null);
    }

    private static JLabel label(String t) { JLabel l = new JLabel(t); l.setForeground(Theme.INK); return l; }

    private void loadProfile() {
        String n = (String) profiles.getSelectedItem();
        if (n == null) return;
        try {
            file = ProfileFile.read(store.file(n));
            dirty = false;
            device.setEnabled(!file.isKeyboard());
            status.setText(file.isKeyboard() ? "Tastiera: «Aggiungi» e premi il tasto." : "Pad: «Aggiungi» e premi il pulsante sul joystick scelto.");
            pad.setBindings(file.bindings);
            showBindings();
        } catch (IOException e) { status.setText("Errore di lettura: " + e.getMessage()); }
    }

    private void showBindings() {
        if (file == null) return;
        String b = pad.selected();
        header.setText(b.toUpperCase().replace('_', ' ') + "  —  " + (file.of(b).isEmpty() ? "nessun tasto assegnato" : file.of(b).size() + " assegnati"));
        bindingModel.clear();
        file.of(b).forEach(bindingModel::addElement);
    }

    private void changed() {
        dirty = true;
        pad.setBindings(file.bindings);
        showBindings();
        status.setText("Modifiche non salvate.");
    }

    private void removeSelected() {
        String tok = bindingList.getSelectedValue();
        if (tok == null || file == null) return;
        file.remove(pad.selected(), tok);
        changed();
    }

    private void save() {
        if (file == null) return;
        try {
            file.write(store.file((String) profiles.getSelectedItem()));
            dirty = false;
            status.setText("Salvato in " + store.file((String) profiles.getSelectedItem()) + " (vale dal prossimo avvio del gioco).");
        } catch (IOException | IllegalArgumentException e) {
            status.setText("Non salvato: " + e.getMessage());
        }
    }

    private void capture() {
        if (file == null || capturing) return;
        String button = pad.selected();
        capturing = true;
        add.setEnabled(false);
        if (file.isKeyboard()) {
            status.setText("Premi il tasto per " + button.toUpperCase() + "…   (Esc annulla)");
            KeyboardFocusManager mgr = KeyboardFocusManager.getCurrentKeyboardFocusManager();
            mgr.addKeyEventDispatcher(new KeyEventDispatcher() {
                @Override public boolean dispatchKeyEvent(KeyEvent e) {
                    if (e.getID() != KeyEvent.KEY_PRESSED) return true;
                    mgr.removeKeyEventDispatcher(this);
                    if (e.getKeyCode() != KeyEvent.VK_ESCAPE) {
                        String tok = ProfileFile.keyToken(e.getKeyCode());
                        if (tok == null) status.setText("Tasto non supportato: " + KeyEvent.getKeyText(e.getKeyCode()));
                        else { file.add(button, tok); changed(); }
                    } else status.setText("Annullato.");
                    done();
                    return true;
                }
            });
        } else {
            status.setText("Premi un pulsante sul joystick js" + device.getValue() + " (10 s)…");
            GamepadCapture.next((Integer) device.getValue(), 10_000,
                    tok -> SwingUtilities.invokeLater(() -> { file.add(button, tok); changed(); done(); }),
                    err -> SwingUtilities.invokeLater(() -> { status.setText(err); done(); }));
        }
    }

    private void done() { capturing = false; add.setEnabled(true); }
}
