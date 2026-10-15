package local.tabletwebcam;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** Small native control icons, compatible with KitKat without vector support. */
final class UiIcon extends Drawable {
    static final int PRIVACY = 0;
    static final int SWITCH = 1;
    static final int ROTATE = 2;
    private final int kind;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    UiIcon(int kind) {
        this.kind = kind;
        paint.setColor(0xffffffff);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.6f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    @Override public void draw(Canvas canvas) {
        int save = canvas.save();
        canvas.translate(getBounds().left, getBounds().top);
        canvas.scale(getBounds().width() / 24f, getBounds().height() / 24f);
        if (kind == ROTATE) {
            canvas.drawRoundRect(new RectF(5, 7, 19, 17), 2, 2, paint);
            canvas.drawLine(15, 7, 15, 17, paint);
            canvas.drawArc(new RectF(1, 1, 23, 23), 200, 130, false, paint);
            canvas.drawLine(21.5f, 6.5f, 17.8f, 6.2f, paint);
            canvas.drawLine(21.5f, 6.5f, 21.1f, 2.8f, paint);
            canvas.restoreToCount(save);
            return;
        }
        canvas.drawRoundRect(new RectF(5, 8, 19, 18), 2, 2, paint);
        canvas.drawCircle(12, 13, 2.8f, paint);
        canvas.drawLine(9, 8, 10, 6, paint);
        canvas.drawLine(10, 6, 14, 6, paint);
        canvas.drawLine(14, 6, 15, 8, paint);
        if (kind == PRIVACY) {
            canvas.drawLine(3, 3, 21, 21, paint);
        } else {
            canvas.drawArc(new RectF(2, 2, 22, 22), 205, 105, false, paint);
            canvas.drawLine(18.4f, 3.2f, 18.6f, 7, paint);
            canvas.drawLine(18.4f, 3.2f, 14.8f, 3.8f, paint);
            canvas.drawArc(new RectF(2, 2, 22, 22), 25, 105, false, paint);
            canvas.drawLine(5.6f, 20.8f, 5.4f, 17, paint);
            canvas.drawLine(5.6f, 20.8f, 9.2f, 20.2f, paint);
        }
        canvas.restoreToCount(save);
    }

    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
