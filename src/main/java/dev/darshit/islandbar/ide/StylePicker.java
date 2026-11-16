package dev.darshit.islandbar.ide;

import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.scale.JBUIScale;
import com.intellij.util.ui.GraphicsUtil;
import com.intellij.util.ui.JBFont;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import dev.darshit.islandbar.core.PillPalette;
import dev.darshit.islandbar.core.PillStyle;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.awt.Shape;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Arc2D;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Supplier;
import javax.swing.ButtonGroup;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/**
 * The settings page's style picker: one card per {@link PillStyle} in a row, each backed by a {@link JRadioButton} in
 * one {@link ButtonGroup} (arrow keys and space work as for any radio group; a disabled card is skipped). Each card
 * holds a small static preview painted from the pill's own {@link PillPalette}, a title and a one-line description.
 * What may be picked, and what a card previews, comes from {@link StyleChoice}. EDT only.
 */
final class StylePicker extends JPanel {
    private static final int PREVIEW_W = 176, PREVIEW_H = 72;
    private static final double SAMPLE_FRACTION = 0.6;

    private final ButtonGroup group = new ButtonGroup();
    private final Map<PillStyle, Card> cards = new EnumMap<>(PillStyle.class);
    private StyleChoice choice = StyleChoice.of(false);

    StylePicker(Supplier<PillPalette> palette) {
        super(new WrapLayout(JBUI.scale(8), JBUI.scale(8)));
        setOpaque(false);
        for (PillStyle s : PillStyle.values()) {
            Card c = new Card(s, palette);
            cards.put(s, c);
            group.add(c.radio);
            add(c);
        }
        getAccessibleContext().setAccessibleName(IslandBundle.message("settings.style.label"));
        setChoice(choice);
    }

    /** Enables/disables cards, swaps the A card's description for the notch note and its preview for B. */
    void setChoice(StyleChoice c) {
        choice = c;
        for (Card card : cards.values()) card.update(c);
    }

    /** Selects the saved style, even one the current choice does not allow (a saved A on a notched Mac). */
    void select(PillStyle s) { cards.get(s).radio.setSelected(true); }

    PillStyle selected() {
        for (Card c : cards.values()) if (c.radio.isSelected()) return c.style;
        return PillStyle.B;
    }

    /** Package-private for tests. */
    JRadioButton radio(PillStyle s) { return cards.get(s).radio; }

    /** Package-private for tests: the text the card shows under its title. */
    String descriptionOf(PillStyle s) { return cards.get(s).descriptionText; }

    /** Package-private for tests: the style the card's preview draws. */
    PillStyle previewOf(PillStyle s) { return cards.get(s).preview.style; }


    // ------------------------------------------------------------------ layout

    /**
     * Equal-size cards, as many per row as fit the width the picker currently has, the rest on further rows. The
     * preferred height follows the current width (the full single row while the width is still unknown), and the
     * preferred width is one card, so a narrow dialog wraps instead of growing a horizontal scrollbar. When a resize
     * changes the row count, the parents are revalidated once so the page reserves the new height.
     */
    static final class WrapLayout implements LayoutManager {
        private final int hgap, vgap;
        private int lastRows = -1;

        WrapLayout(int hgap, int vgap) { this.hgap = hgap; this.vgap = vgap; }

        private static Dimension cell(Container c) { // the largest preferred card size: all cards get it
            int w = 0, h = 0;
            for (Component k : c.getComponents()) {
                if (!k.isVisible()) continue;
                Dimension d = k.getPreferredSize();
                w = Math.max(w, d.width);
                h = Math.max(h, d.height);
            }
            return new Dimension(w, h);
        }

        private static int count(Container c) {
            int n = 0;
            for (Component k : c.getComponents()) if (k.isVisible()) n++;
            return n;
        }

        private int columns(int innerWidth, Dimension cell, int n) {
            if (innerWidth <= 0) return Math.max(1, n); // width not known yet: one row
            return Math.max(1, Math.min(n, (innerWidth + hgap) / (cell.width + hgap)));
        }

        private Dimension size(Container c) {
            Insets in = c.getInsets();
            Dimension cell = cell(c);
            int n = count(c);
            if (n == 0) return new Dimension(in.left + in.right, in.top + in.bottom);
            int cols = columns(c.getWidth() - in.left - in.right, cell, n);
            int rows = (n + cols - 1) / cols;
            int w = cell.width;
            return new Dimension(in.left + in.right + w, in.top + in.bottom + rows * cell.height + (rows - 1) * vgap);
        }

        @Override public Dimension preferredLayoutSize(Container c) { return size(c); }
        @Override public Dimension minimumLayoutSize(Container c) { return size(c); }
        @Override public void addLayoutComponent(String name, Component comp) {}
        @Override public void removeLayoutComponent(Component comp) {}

        @Override public void layoutContainer(Container c) {
            Insets in = c.getInsets();
            Dimension cell = cell(c);
            int n = count(c);
            int cols = columns(c.getWidth() - in.left - in.right, cell, n);
            int i = 0;
            for (Component k : c.getComponents()) {
                if (!k.isVisible()) continue;
                int col = i % cols, row = i / cols;
                k.setBounds(in.left + col * (cell.width + hgap), in.top + row * (cell.height + vgap), cell.width, cell.height);
                i++;
            }
            int rows = n == 0 ? 0 : (n + cols - 1) / cols;
            if (rows != lastRows) {
                lastRows = rows;
                SwingUtilities.invokeLater(c::revalidate); // not inside this layout pass: the parents re-read our height
            }
        }
    }

    // ------------------------------------------------------------------ card

    private final class Card extends JPanel {
        final PillStyle style;
        final JRadioButton radio;
        final Preview preview;
        private final JBLabel description;
        String descriptionText = "";

        Card(PillStyle style, Supplier<PillPalette> palette) {
            super(new BorderLayout(0, JBUI.scale(4)));
            this.style = style;
            setOpaque(false);
            setBorder(JBUI.Borders.empty(8));
            preview = new Preview(palette);
            radio = new JRadioButton(IslandBundle.message("settings.style." + style.name() + ".title"));
            radio.setOpaque(false);
            radio.addItemListener(_ -> StylePicker.this.repaint());
            description = new JBLabel() {
                @Override public void updateUI() { // keep the comment look across theme switches
                    super.updateUI();
                    setFont(JBFont.small());
                    setForeground(UIUtil.getContextHelpForeground());
                }
            };
            description.setVerticalAlignment(JBLabel.TOP);
            JPanel text = new JPanel(new BorderLayout(0, JBUI.scale(2)));
            text.setOpaque(false);
            text.add(radio, BorderLayout.NORTH);
            text.add(description, BorderLayout.CENTER);
            add(preview, BorderLayout.NORTH);
            add(text, BorderLayout.CENTER);
            MouseAdapter click = new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) {
                    if (!radio.isEnabled()) return;
                    radio.setSelected(true);
                    radio.requestFocusInWindow();
                }
            };
            addMouseListener(click);
            preview.addMouseListener(click);
            description.addMouseListener(click);
        }

        void update(StyleChoice c) {
            boolean selectable = c.selectable(style);
            radio.setEnabled(selectable);
            preview.style = c.preview(style);
            preview.dimmed = !selectable;
            String key = c.showNotchNote(style) ? "settings.style.notch" : "settings.style." + style.name() + ".description";
            descriptionText = IslandBundle.message(key);
            description.setText("<html><body style='width:" + JBUI.scale(PREVIEW_W) + "px'>"
                    + StringUtil.escapeXmlEntities(descriptionText) + "</body></html>");
            String title = radio.getText();
            radio.getAccessibleContext().setAccessibleDescription(descriptionText);
            getAccessibleContext().setAccessibleName(title);
            getAccessibleContext().setAccessibleDescription(descriptionText);
            revalidate();
            repaint();
        }

        /** The selected card gets a 2 px accent outline, the others the theme's border colour; read at paint time. */
        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                GraphicsUtil.setupAAPainting(g2);
                boolean sel = radio.isSelected();
                float t = JBUIScale.scale(sel ? 2f : 1f);
                float arc = JBUIScale.scale(10f);
                g2.setColor(sel ? JBUI.CurrentTheme.Focus.focusColor() : JBColor.border());
                g2.setStroke(new BasicStroke(t));
                g2.draw(new RoundRectangle2D.Float(t / 2, t / 2, getWidth() - t, getHeight() - t, arc, arc));
            } finally {
                g2.dispose();
            }
        }
    }

    // ------------------------------------------------------------------ preview

    /** A static, cheap drawing of one pill style over a mini menu bar: no timers, no native code, no animation. */
    static final class Preview extends JComponent {
        private final Supplier<PillPalette> palette;
        PillStyle style = PillStyle.B;
        boolean dimmed;

        Preview(Supplier<PillPalette> palette) {
            this.palette = palette;
            setPreferredSize(JBUI.size(PREVIEW_W, PREVIEW_H));
            setMinimumSize(JBUI.size(PREVIEW_W, PREVIEW_H));
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                GraphicsUtil.setupAAPainting(g2);
                GraphicsUtil.setupAntialiasing(g2);
                if (dimmed) g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.45f));
                paintPreview(g2, style, palette.get(), getWidth(), getHeight());
            } finally {
                g2.dispose();
            }
        }

        static void paintPreview(Graphics2D g, PillStyle style, PillPalette p, int w, int h) {
            Color text = new Color(p.text()), bg = new Color(p.background());
            Color secondary = new Color(p.secondaryText()), outline = new Color(p.separator());
            Color screen = UIUtil.getEditorPaneBackground();
            Color progress = color("ProgressBar.progressColor", JBUI.CurrentTheme.Focus.focusColor());
            Color track = color("ProgressBar.trackColor", outline);

            // the "screen": a window backdrop with a mini menu bar on top
            float r = JBUIScale.scale(6f);
            Shape frame = new RoundRectangle2D.Float(0.5f, 0.5f, w - 1, h - 1, r, r);
            g.setColor(screen);
            g.fill(frame);
            int mb = JBUI.scale(14);
            Shape oldClip = g.getClip();
            g.clip(frame);
            g.setColor(mix(text, screen, 0.10));
            g.fillRect(0, 0, w, mb);
            g.setColor(mix(text, screen, 0.35));
            int x = JBUI.scale(8), itemH = JBUI.scale(3), itemY = (mb - itemH) / 2;
            for (int len : new int[]{6, 14, 10, 10, 12}) { // menu titles on the left
                g.fillRoundRect(x, itemY, JBUI.scale(len), itemH, itemH, itemH);
                x += JBUI.scale(len + 5);
            }
            for (int i = 1; i <= 2; i++) g.fillRoundRect(w - JBUI.scale(14 * i), itemY, JBUI.scale(9), itemH, itemH, itemH);
            g.setClip(oldClip);
            g.setColor(JBColor.border());
            g.draw(frame);

            String name = IslandBundle.message("settings.style.preview.task");
            String pct = Math.round(SAMPLE_FRACTION * 100) + "%";
            Font font = JBFont.label().deriveFont(JBUIScale.scale(9f));
            g.setFont(font);
            FontMetrics fm = g.getFontMetrics();
            int pad = JBUI.scale(7);
            switch (style) {
                case A -> { // one row inside the menu bar
                    int pw = Math.round(w * 0.62f), px = (w - pw) / 2;
                    pill(g, px, 0, pw, mb, JBUI.scale(5), bg, outline);
                    int base = (mb + fm.getAscent() - fm.getDescent()) / 2;
                    int pctW = fm.stringWidth(pct);
                    String n = ellipsize(name, fm, Math.round(pw * 0.38f));
                    g.setColor(text);
                    g.drawString(n, px + pad, base);
                    g.setColor(secondary);
                    g.drawString(pct, px + pw - pad - pctW, base);
                    int bx = px + pad + fm.stringWidth(n) + JBUI.scale(5);
                    int bw = px + pw - pad - pctW - JBUI.scale(5) - bx;
                    bar(g, bx, (mb - JBUI.scale(3)) / 2, bw, JBUI.scale(3), track, progress);
                }
                case B -> { // name and percentage over the bar, hanging below the menu bar
                    int pw = Math.round(w * 0.66f), px = (w - pw) / 2, ph = JBUI.scale(30);
                    pill(g, px, mb, pw, ph, JBUI.scale(9), bg, outline);
                    int base = mb + JBUI.scale(5) + fm.getAscent();
                    int pctW = fm.stringWidth(pct);
                    g.setColor(text);
                    g.drawString(ellipsize(name, fm, pw - 2 * pad - pctW - JBUI.scale(6)), px + pad, base);
                    g.setColor(secondary);
                    g.drawString(pct, px + pw - pad - pctW, base);
                    bar(g, px + pad, mb + ph - JBUI.scale(10), pw - 2 * pad, JBUI.scale(4), track, progress);
                }
                case C -> { // a ring and the percentage
                    int pctW = fm.stringWidth(pct), ring = JBUI.scale(12), gap = JBUI.scale(4);
                    int pw = pad + ring + gap + pctW + pad, ph = JBUI.scale(22), px = (w - pw) / 2;
                    pill(g, px, mb, pw, ph, JBUI.scale(11), bg, outline);
                    float sw = JBUIScale.scale(2.5f);
                    float rx = px + pad + sw / 2, ry = mb + (ph - ring) / 2f + sw / 2, d = ring - sw;
                    g.setStroke(new BasicStroke(sw, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.setColor(track);
                    g.draw(new Ellipse2D.Float(rx, ry, d, d));
                    g.setColor(progress);
                    g.draw(new Arc2D.Float(rx, ry, d, d, 90, (float) (-360 * SAMPLE_FRACTION), Arc2D.OPEN));
                    g.setColor(secondary);
                    g.drawString(pct, px + pad + ring + gap, mb + (ph + fm.getAscent() - fm.getDescent()) / 2);
                }
            }
        }

        /** A pill with square top corners and rounded bottom ones, as it hangs from the top of the screen. */
        private static void pill(Graphics2D g, int x, int y, int w, int h, int r, Color fill, Color outline) {
            Area a = new Area(new RoundRectangle2D.Float(x, y - r, w, h + r, 2 * r, 2 * r));
            a.intersect(new Area(new Rectangle2D.Float(x, y, w, h)));
            g.setColor(fill);
            g.fill(a);
            g.setColor(outline);
            g.setStroke(new BasicStroke(JBUIScale.scale(1f)));
            g.draw(a);
        }

        private static void bar(Graphics2D g, int x, int y, int w, int h, Color track, Color progress) {
            if (w <= 0) return;
            g.setColor(track);
            g.fillRoundRect(x, y, w, h, h, h);
            g.setColor(progress);
            g.fillRoundRect(x, y, (int) Math.round(w * SAMPLE_FRACTION), h, h, h);
        }

        private static String ellipsize(String s, FontMetrics fm, int max) {
            if (fm.stringWidth(s) <= max) return s;
            for (int n = s.length() - 1; n > 0; n--) {
                String t = s.substring(0, n) + "…";
                if (fm.stringWidth(t) <= max) return t;
            }
            return "…";
        }

        private static Color color(String key, Color fallback) {
            Color c = UIManager.getColor(key);
            return c != null ? c : fallback;
        }

        /** {@code fg} weighted by {@code amount} over {@code bg}. */
        private static Color mix(Color fg, Color bg, double amount) {
            return new Color((int) Math.round(fg.getRed() * amount + bg.getRed() * (1 - amount)),
                    (int) Math.round(fg.getGreen() * amount + bg.getGreen() * (1 - amount)),
                    (int) Math.round(fg.getBlue() * amount + bg.getBlue() * (1 - amount)));
        }
    }
}
