package com.miaojian.wuwalauncher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 触摸轨迹。
 *
 * 测试档位时用户滑动屏幕，这里实时画出手指走过的路径 ——
 * 轨迹跟不跟手，直接反映该分辨率下触摸是否正常。
 *
 * 渐隐方式：按每个点自己的时间戳算透明度，所以是**笔尖清楚、笔尾先淡**，
 * 整条轨迹像彗星尾巴一样随时间消散，而不是整条一起变淡。
 *
 * 布局约束：本 View 必须放在遮罩内容的**下面**（先 addView）。
 * 它只接收没被上层消费的触摸事件，所以「确认无误」按钮照常可点。
 */
public class TouchTrailView extends View {

    /** 一个采样点，带自己的出生时间 */
    private static final class Pt {
        final float x;
        final float y;
        final long t;

        Pt(float x, float y, long t) {
            this.x = x;
            this.y = y;
            this.t = t;
        }
    }

    private static final class Stroke {
        final List<Pt> points = new ArrayList<Pt>();

        boolean allExpired(long now, long life) {
            // 最后一个点也过期了才算整条消失
            if (points.isEmpty()) return true;
            return now - points.get(points.size() - 1).t > life;
        }
    }

    private static final int MAX_STROKES = 12;
    /** 单个点存活时长，也是拖尾长度 */
    private static final long POINT_LIFE_MS = 900;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<Stroke> strokes = new ArrayList<Stroke>();
    private Stroke current;

    private final Runnable animator = new Runnable() {
        public void run() {
            invalidate();
            if (!strokes.isEmpty()) postDelayed(this, 16);
        }
    };

    public TouchTrailView(Context c) {
        super(c);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeWidth(Ui.dp(c, 4.5f));
        paint.setColor(0xFF4ADE80);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        long now = System.currentTimeMillis();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                startStroke(e.getX(), e.getY(), now);
                return true;

            case MotionEvent.ACTION_MOVE:
                if (current != null) {
                    // 一个 MOVE 事件可能包含多个历史点，全部收下才够顺滑
                    int n = e.getHistorySize();
                    for (int i = 0; i < n; i++) {
                        addPoint(current, e.getHistoricalX(i), e.getHistoricalY(i), now);
                    }
                    addPoint(current, e.getX(), e.getY(), now);
                }
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                current = null;
                return true;

            default:
                return true;
        }
    }

    private void startStroke(float x, float y, long now) {
        if (strokes.size() >= MAX_STROKES) {
            strokes.remove(0);
        }
        current = new Stroke();
        current.points.add(new Pt(x, y, now));
        strokes.add(current);
        kick();
    }

    private void addPoint(Stroke s, float x, float y, long now) {
        s.points.add(new Pt(x, y, now));

        // 淘汰已经过寿命的点。
        //
        // 不再限制总点数：手指一直滑时如果按数量截断，划到一半轨迹就断了，
        // 而那正是用户最想看清楚的时候。按时间淘汰既不会断，也不会越长越乱 ——
        // 超过 POINT_LIFE_MS 的点本来就已经淡到看不见，留着只是占内存。
        while (s.points.size() > 2 && now - s.points.get(0).t > POINT_LIFE_MS) {
            s.points.remove(0);
        }
        kick();
    }

    private void kick() {
        removeCallbacks(animator);
        post(animator);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        long now = System.currentTimeMillis();

        for (Iterator<Stroke> it = strokes.iterator(); it.hasNext(); ) {
            Stroke s = it.next();
            if (s.allExpired(now, POINT_LIFE_MS)) {
                it.remove();
                if (s == current) current = null;
                continue;
            }

            List<Pt> pts = s.points;
            for (int i = 1; i < pts.size(); i++) {
                Pt p0 = pts.get(i - 1);
                Pt p1 = pts.get(i);
                long age = now - p1.t;
                if (age >= POINT_LIFE_MS) continue;

                // 每个点按自己的年龄决定透明度：越旧越淡
                float k = 1f - age / (float) POINT_LIFE_MS;
                paint.setAlpha((int) (255 * k * k));   // 平方让尾部收得更快，更像拖尾
                paint.setStrokeWidth(Ui.dp(getContext(), 1.5f + 3f * k));
                canvas.drawLine(p0.x, p0.y, p1.x, p1.y, paint);
            }

            // 单独把最新的那个点画成一个小圆点，笔尖更清楚
            if (!pts.isEmpty()) {
                Pt last = pts.get(pts.size() - 1);
                long age = now - last.t;
                if (age < POINT_LIFE_MS) {
                    float k = 1f - age / (float) POINT_LIFE_MS;
                    paint.setAlpha((int) (255 * k));
                    paint.setStrokeWidth(Ui.dp(getContext(), 5f * k));
                    canvas.drawPoint(last.x, last.y, paint);
                }
            }
        }

        // 重绘调度统一由 animator 负责，这里不重复 post，避免两个循环叠加
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(animator);
        super.onDetachedFromWindow();
    }
}
