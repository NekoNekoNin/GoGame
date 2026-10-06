import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;

/** Native 20px MCphone-style sprite. Run from the project root with java tools/GeneratePixelIcon.java. */
public final class GeneratePixelIcon {
    private static final int SIZE = 20;

    private static boolean inside(int x, int y) {
        int row = Math.min(y, SIZE - 1 - y);
        int inset = row == 0 ? 3 : row == 1 ? 2 : row == 2 ? 1 : 0;
        return x >= inset && x < SIZE - inset;
    }

    private static void pixel(BufferedImage image, int x, int y, int rgb) {
        image.setRGB(x, y, 0xFF000000 | rgb);
    }

    private static void stone(BufferedImage image, int x, int y, boolean black) {
        // Identical five-pixel silhouettes; centers (6,6) and (13,13) mirror exactly.
        String[] shape = {".eee.", "ebhbe", "ebbbe", "essse", ".eee."};
        int edge = black ? 0x111519 : 0x847B66;
        int body = black ? 0x25282C : 0xF5F4E3;
        int light = black ? 0x62696D : 0xFFFFFF;
        int shade = black ? 0x1B1F23 : 0xC7D1C5;
        for (int dy = 0; dy < 5; dy++) {
            for (int dx = 0; dx < 5; dx++) {
                if (shape[dy].charAt(dx) != '.') pixel(image, x + dx + 1, y + dy + 1, 0xB78346);
            }
        }
        for (int dy = 0; dy < 5; dy++) {
            for (int dx = 0; dx < 5; dx++) {
                char p = shape[dy].charAt(dx);
                if (p != '.') pixel(image, x + dx, y + dy, switch (p) {
                    case 'e' -> edge;
                    case 'h' -> light;
                    case 's' -> shade;
                    default -> body;
                });
            }
        }
        pixel(image, x + 1, y + 1, light);
    }

    private static BufferedImage icon() {
        BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                if (!inside(x, y)) continue;
                int wood = y < 10 ? 0xF0BE73 : 0xE6AE62;
                if (x == 0 || x == 19 || y == 0 || y == 19) wood = 0x996233;
                else if (x == 18 || y == 18) wood = 0xBD8140;
                else if (x == 1 || y == 1) wood = 0xFFDA8B;
                pixel(image, x, y, wood);
            }
        }
        // Spare, centered wood accents; no noise or blurred anti-aliasing.
        pixel(image, 2, 2, 0xFFDA8B); pixel(image, 17, 2, 0xFFDA8B);
        pixel(image, 2, 17, 0xBD8140); pixel(image, 17, 17, 0xBD8140);
        for (int x : new int[]{5, 14}) {
            pixel(image, x, 2, 0xE6AE62); pixel(image, x, 17, 0xF0BE73);
        }
        // Equal three-pixel margins; the middle line of an even sprite is half a pixel off center.
        for (int line : new int[]{3, 6, 9, 13, 16}) {
            for (int i = 3; i <= 16; i++) {
                pixel(image, line, i, 0x85572E);
                pixel(image, i, line, 0x85572E);
            }
        }
        stone(image, 4, 4, true);
        stone(image, 11, 11, false);
        return image;
    }

    public static void main(String[] args) throws Exception {
        Path resources = Path.of("src/main/resources");
        Path texture = resources.resolve("assets/gogame/textures/app/go-v2.png");
        BufferedImage icon = icon();
        ImageIO.write(icon, "png", texture.toFile());
        ImageIO.write(icon, "png", resources.resolve("gogame-logo.png").toFile());
        Files.writeString(Path.of(texture + ".mcmeta"), "{\"texture\":{\"blur\":false,\"clamp\":true}}\n");

        Path previews = Path.of("../docs/ui-preview");
        Files.createDirectories(previews);
        BufferedImage enlarged = new BufferedImage(160, 160, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = enlarged.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(icon, 0, 0, 160, 160, null); g.dispose();
        ImageIO.write(enlarged, "png", previews.resolve("icon-pixel.png").toFile());

        // Comparison uses the authoritative 1.9.3 JAR, without copying its assets into the addon.
        BufferedImage strip = new BufferedImage(592, 144, BufferedImage.TYPE_INT_ARGB);
        g = strip.createGraphics();
        g.setColor(new Color(0x0F1442)); g.fillRect(0, 0, strip.getWidth(), strip.getHeight());
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        try (ZipFile jar = new ZipFile("libs/mcphone-1.9.3.jar")) {
            String[] names = {"settings", "app_store", null, "clock", "weather"};
            for (int i = 0; i < names.length; i++) {
                BufferedImage sprite = icon;
                if (names[i] != null) {
                    try (InputStream in = jar.getInputStream(jar.getEntry("assets/mcphone/textures/app/" + names[i] + ".png"))) {
                        sprite = ImageIO.read(in);
                    }
                }
                g.drawImage(sprite, (8 + i * 28) * 4, 32, 80, 80, null);
            }
        }
        g.dispose();
        ImageIO.write(strip, "png", previews.resolve("icon-pixel-strip.png").toFile());
        System.out.println("Generated 20x20 icon, matching root Logo, and nearest-neighbor previews.");
    }
}
