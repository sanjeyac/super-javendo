package dev.superjavendo.frontend.modern;

import static dev.superjavendo.i18n.I18n.t;

import dev.superjavendo.config.AppPaths;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Right column of the ROM Manager: the selected file, or the group of files of the same game, with the actions on it. */
final class RomDetail extends JPanel {
    /** What the buttons do; the screen implements it on top of {@link LibraryData}. */
    interface Actions {
        void setMain(Game g);
        void hide(List<Game> games, boolean hide);
        void trash(Game g);
        void changeCover(Game g);
    }

    private final Covers covers;
    private final AppPaths paths;
    private final Actions actions;
    private final JPanel body = new Widgets.Body();
    private final JPanel foot = new JPanel();

    RomDetail(Covers covers, AppPaths paths, Actions actions) {
        super(new BorderLayout());
        this.covers = covers;
        this.paths = paths;
        this.actions = actions;
        setBackground(Pal.SIDE);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBackground(Pal.SIDE);
        body.setBorder(new EmptyBorder(24, 20, 12, 20));
        foot.setLayout(new BoxLayout(foot, BoxLayout.Y_AXIS));
        foot.setBackground(Pal.SIDE);
        foot.setBorder(new EmptyBorder(8, 20, 20, 20));
        add(Widgets.scroll(body, Pal.SIDE), BorderLayout.CENTER);
        add(foot, BorderLayout.SOUTH);
    }

    private static final String W = "<html><body style='width:270px'>";

    private static JLabel text(String s, Font f, Color c) {
        JLabel l = Widgets.label(W + s.replace("&", "&amp;").replace("<", "&lt;") + "</body></html>", f, c);
        l.setAlignmentX(LEFT_ALIGNMENT);
        return l;
    }

    private static JComponent caption(String s) {
        JComponent c = new JComponent() {
            @Override protected void paintComponent(Graphics g) { Graphics2D g2 = Ui.g2(g); Ui.label(g2, s, 0, 14); g2.dispose(); }
        };
        c.setPreferredSize(new Dimension(100, 22));
        c.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
        c.setAlignmentX(LEFT_ALIGNMENT);
        return c;
    }

    private void gap(int h) { body.add(Box.createVerticalStrut(h)); }

    private static Ui.Btn button(String text, Color bg, Color fg, Color line, Runnable r) {
        Ui.Btn b = new Ui.Btn(text, bg, fg, line).onClick(r);
        b.setPreferredSize(new Dimension(100, 46));
        b.setMaximumSize(new Dimension(Integer.MAX_VALUE, 46));
        b.setAlignmentX(LEFT_ALIGNMENT);
        return b;
    }

    /** Shows the selection. {@code cat} resolves the group of the selected file. */
    void show(List<RomCatalog.Row> selection, RomCatalog cat) {
        body.removeAll();
        foot.removeAll();
        if (selection.isEmpty()) {
            body.add(caption(t("rd.file")));
            body.add(text(t("rd.select"), Pal.ui(14, 0), Pal.TXT2));
        } else if (selection.size() > 1) {
            body.add(caption(t("rd.selection")));
            body.add(text(t("rd.nfiles", selection.size()), Pal.title(24), Pal.TXT));
            gap(8);
            body.add(text(t("rd.multihint"), Pal.ui(14, 0), Pal.TXT2));
        } else {
            single(selection.get(0), cat);
        }
        body.add(Box.createVerticalGlue());
        revalidate();
        repaint();
    }

    private void single(RomCatalog.Row r, RomCatalog cat) {
        Game g = r.game();
        List<RomCatalog.Row> group = cat.group(g);
        boolean multi = group.size() > 1;
        body.add(caption(multi ? t("rd.group") : t("rd.file")));
        body.add(text(g.title() + (multi ? " · " + t("rd.nfiles", group.size()) : ""), Pal.title(24), Pal.TXT));
        gap(6);
        body.add(text(multi ? t("rd.grouphint") : g.fileName(), Pal.ui(14, 0), Pal.TXT2));
        gap(14);
        for (RomCatalog.Row o : group) {
            body.add(new FileOption(o, o.main(), o.game().path().equals(g.path()), states(o.game().path()), multi));
            gap(8);
        }
        gap(8);

        body.add(caption(t("rd.cover")));
        gap(6);
        body.add(coverRow(g));
        gap(18);

        body.add(caption(t("rd.metamatch")));
        gap(6);
        body.add(text(cat.metadataLoaded() ? t("rd.metarow") : t("rd.nometafile"), Pal.ui(13, 0), Pal.TXT2));
        gap(6);
        body.add(metaBox(r, cat.metadataLoaded()));

        // actions: on the duplicate if there is one, else on the file itself
        RomCatalog.Row target = r;
        if (multi && r.main()) for (RomCatalog.Row o : group) if (!o.main() && !o.hidden()) { target = o; break; }
        final Game tg = target.game();
        final boolean hid = target.hidden();
        String hideText = hid ? t("act.show") : multi && !target.main() ? t("act.hidedup") : t("act.hide");
        foot.add(button(hideText, Pal.ACCENT, Color.WHITE, null, () -> actions.hide(List.of(tg), !hid)));
        foot.add(Box.createVerticalStrut(8));
        String tag = tg.badTag();
        String trashText = multi ? (tag.isEmpty() ? t("act.trashdup") : t("act.trashtag", tag)) : t("act.trash");
        foot.add(button(trashText, null, Pal.ERR, new Color(0x6a3340), () -> actions.trash(tg)));
    }

    private int states(Path rom) {
        int n = 0;
        for (int s = 0; s <= AppPaths.MAX_SLOT; s++) if (Files.exists(paths.stateFile(rom, s))) n++;
        return n;
    }

    // ---------------------------------------------------------------------------------------- pieces

    /** One file of a group: radio, name, dump quality and save states. */
    private final class FileOption extends JComponent {
        private final RomCatalog.Row row;
        private final boolean main, focus, group;
        private final int states;

        FileOption(RomCatalog.Row row, boolean main, boolean focus, int states, boolean group) {
            this.row = row; this.main = main; this.focus = focus; this.states = states; this.group = group;
            setPreferredSize(new Dimension(100, 82));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 82));
            setAlignmentX(LEFT_ALIGNMENT);
            if (group) {
                setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                addMouseListener(new MouseAdapter() {
                    @Override public void mousePressed(MouseEvent e) { if (!row.hidden()) actions.setMain(row.game()); }
                });
            }
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = Ui.g2(g);
            Ui.fillRound(g2, 0, 0, getWidth(), getHeight(), 14, main && group ? new Color(0x1f2040) : Pal.CARD, main && group ? Pal.ACCENT : focus ? Pal.CARD_LINE.brighter() : Pal.CARD_LINE);
            int x = 16;
            if (group) {
                g2.setColor(main ? Pal.ACCENT_L : Pal.TXT3);
                g2.setStroke(new BasicStroke(1.6f));
                g2.drawOval(14, 18, 14, 14);
                if (main) g2.fillOval(18, 22, 6, 6);
                x = 42;
            }
            FontMetrics mm = g2.getFontMetrics(Pal.mono(13));
            Ui.text(g2, Ui.ellipsize(mm, row.game().fileName(), getWidth() - x - 12), x, 30, Pal.mono(13), row.hidden() ? Pal.TXT3 : Pal.TXT);
            String crc = row.game().crc32() == null ? "" : " · CRC " + row.game().crc32();
            String q; Color qc;
            switch (row.game().quality()) {
                case VERIFIED -> { q = t("dump.good") + crc; qc = Pal.OK; }
                case BAD -> { q = t("dump.bad") + " [" + row.game().badTag() + "]"; qc = Pal.ERR; }
                default -> { q = t("dump.unknown") + crc; qc = Pal.TXT2; }
            }
            Font f = Pal.ui(13, 0);
            Ui.text(g2, Ui.ellipsize(g2.getFontMetrics(f), q, getWidth() - x - 12), x, 50, f, qc);
            String st = (group && main ? t("rd.mainfile") + " · " : row.hidden() ? t("rd.hiddenfile") + " · " : "") + (states == 0 ? t("detail.nostate") : t(states == 1 ? "rd.states1" : "rd.statesn", states));
            Ui.text(g2, st, x, 70, f, Pal.TXT2);
            g2.dispose();
        }
    }

    private JComponent coverRow(Game g) {
        JPanel p = new JPanel(new BorderLayout(14, 0));
        p.setOpaque(false);
        p.setAlignmentX(LEFT_ALIGNMENT);
        p.setPreferredSize(new Dimension(100, 112));
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 112));
        JComponent thumb = new JComponent() {
            @Override protected void paintComponent(Graphics gg) {
                Graphics2D g2 = Ui.g2(gg);
                var img = covers.get(g.path());
                if (img != null) Ui.fit(g2, img, 0, 0, getWidth(), getHeight(), 10);
                else { Ui.fillRound(g2, 0, 0, getWidth(), getHeight(), 10, Pal.CARD, null); Ui.dashedRound(g2, 0, 0, getWidth(), getHeight(), 10, Pal.CARD_LINE); g2.setColor(Pal.TXT3); Ui.pictureIcon(g2, getWidth() / 2, getHeight() / 2, Pal.TXT3); }
                g2.dispose();
            }
        };
        thumb.setPreferredSize(new Dimension(84, 112));
        p.add(thumb, BorderLayout.WEST);
        JPanel side = new JPanel();
        side.setOpaque(false);
        side.setLayout(new BoxLayout(side, BoxLayout.Y_AXIS));
        Path f = covers.coverFile(g.path());
        JLabel name = Widgets.label(f == null ? t("rd.nocover") : f.getFileName().toString(), Pal.mono(12), f == null ? Pal.WARN : Pal.TXT);
        name.setAlignmentX(LEFT_ALIGNMENT);
        side.add(Box.createVerticalGlue());
        side.add(name);
        side.add(Box.createVerticalStrut(6));
        JLabel link = Widgets.label("<html><u>" + (f == null ? t("rd.pickimage") : t("rd.changeimage")) + "</u></html>", Pal.ui(13, 0), Pal.ACCENT_L);
        link.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        link.setAlignmentX(LEFT_ALIGNMENT);
        link.addMouseListener(new MouseAdapter() { @Override public void mousePressed(MouseEvent e) { actions.changeCover(g); } });
        side.add(link);
        side.add(Box.createVerticalGlue());
        p.add(side, BorderLayout.CENTER);
        return p;
    }

    private JComponent metaBox(RomCatalog.Row r, boolean loaded) {
        Game g = r.game();
        String s;
        if (r.hasMeta()) {
            List<String> parts = new java.util.ArrayList<>();
            parts.add(g.title());
            if (g.year() > 0) parts.add(String.valueOf(g.year()));
            if (!g.publisher().isEmpty()) parts.add(g.publisher());
            if (!g.genre().isEmpty()) parts.add(g.genre());
            s = String.join(" · ", parts);
        } else if (!loaded) s = "—";
        else s = g.crc32() == null ? t("rd.crcwait") : t("rd.nomatch", g.crc32());
        final String shown = s;
        JLabel l = new JLabel(W.replace("270", "240") + shown.replace("&", "&amp;").replace("<", "&lt;") + "</body></html>") {
            @Override protected void paintComponent(Graphics gg) {
                Graphics2D g2 = Ui.g2(gg);
                Ui.fillRound(g2, 0, 0, getWidth(), getHeight(), 12, Pal.CARD, Pal.CARD_LINE);
                g2.dispose();
                super.paintComponent(gg);
            }
        };
        l.setFont(Pal.ui(14, 0));
        l.setForeground(Pal.TXT);
        l.setBorder(new EmptyBorder(12, 14, 12, 14));
        l.setAlignmentX(LEFT_ALIGNMENT);
        l.setMaximumSize(new Dimension(Integer.MAX_VALUE, 200));
        return l;
    }
}
