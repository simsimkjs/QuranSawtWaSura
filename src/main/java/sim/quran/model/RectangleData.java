package sim.quran.model;

/**
 * بيانات مستطيل واحد على الصورة (إحداثيات نسبية 0-1)
 */
public class RectangleData {
    public final double x;
    public final double y;
    public final double width;
    public final double height;
    
    public RectangleData(double x, double y, double width, double height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }
    
    public boolean isValid() {
        return x >= 0 && y >= 0 && width > 0.001 && height > 0.001 
            && x + width <= 1.0 && y + height <= 1.0;
    }
    
    @Override
    public String toString() {
        return String.format("Rect(%.3f, %.3f, %.3f, %.3f)", x, y, width, height);
    }
}