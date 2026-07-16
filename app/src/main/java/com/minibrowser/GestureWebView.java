package com.minibrowser;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.webkit.WebView;

/**
 * A minimal WebView extension that detects *edge* swipes using pure vector math.
 *
 *  - Start on the LEFT  edge, swipe RIGHT  -> navigate Back
 *  - Start on the RIGHT edge, swipe LEFT   -> navigate Forward
 *
 * No gesture libraries are used. Only ACTION_DOWN / ACTION_UP coordinates are
 * recorded; the page still receives the full motion sequence via super so that
 * normal scrolling, links and text-selection are completely unaffected.
 *
 * Complexity: O(1) per motion event.
 */
public class GestureWebView extends WebView {

    public interface GestureListener {
        void onEdgeBack();
        void onEdgeForward();
    }

    public interface ScrollListener {
        void onScrolled(int dx, int dy, boolean atTop);
    }

    private GestureListener gestureListener;
    private ScrollListener scrollListener;

    @Override
    protected void onScrollChanged(int l, int t, int oldl, int oldt) {
        super.onScrollChanged(l, t, oldl, oldt);
        if (scrollListener != null) {
            scrollListener.onScrolled(l - oldl, t - oldt, t <= 0);
        }
    }

    private final float edgeSize;        // px zone from the screen edge (scaled by density)
    private final float swipeThreshold;  // min |dx| to count as a swipe
    private final long  swipeTimeout;    // max ms for a gesture
    private final float touchSlop;

    private float downX = -1;
    private float downY = -1;
    private long  downTime = 0;
    private boolean maybeGesture = false;

    public GestureWebView(Context context) {
        this(context, null);
    }

    public GestureWebView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public GestureWebView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        final float density = context.getResources().getDisplayMetrics().density;
        this.edgeSize = context.getResources().getDimension(R.dimen.edge_gesture_dp);
        this.swipeThreshold = 80f * density;
        this.swipeTimeout = 500L;
        this.touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    public void setGestureListener(GestureListener listener) {
        this.gestureListener = listener;
    }

    public void setScrollListener(ScrollListener listener) {
        this.scrollListener = listener;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event == null) {
            return super.onTouchEvent(event);
        }
        final int action = event.getActionMasked();
        final float w = getWidth();

        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                downX = event.getX();
                downY = event.getY();
                downTime = event.getEventTime();
                maybeGesture = (downX >= 0f && downX <= edgeSize)              // left edge
                            || (w > 0f && downX >= (w - edgeSize) && downX <= w); // right edge
                break;
            }
            case MotionEvent.ACTION_UP: {
                if (maybeGesture && downX >= 0f && w > 0f) {
                    final float dx = event.getX() - downX;
                    final float dy = event.getY() - downY;
                    final long  dt = event.getEventTime() - downTime;

                    // Vector check: horizontal-dominant, large enough, quick enough.
                    final boolean leftEdge   = downX <= edgeSize;
                    final boolean rightEdge  = downX >= (w - edgeSize);

                    if (dt < swipeTimeout
                            && Math.abs(dx) > swipeThreshold
                            && Math.abs(dx) > (Math.abs(dy) * 2f)) {

                        if (leftEdge && dx > 0f && gestureListener != null) {
                            // Left edge swiped toward the right.
                            gestureListener.onEdgeBack();
                            performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
                        } else if (rightEdge && dx < 0f && gestureListener != null) {
                            // Right edge swiped toward the left.
                            gestureListener.onEdgeForward();
                            performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
                        }
                    }
                }
                resetGesture();
                break;
            }
            case MotionEvent.ACTION_CANCEL: {
                resetGesture();
                break;
            }
            default: {
                // Cancel the candidate gesture as soon as the finger drifts vertically
                // beyond the touch slop — that means the user is scrolling, not swiping.
                if (maybeGesture && downY >= 0f && Math.abs(event.getY() - downY) > touchSlop + swipeThreshold) {
                    maybeGesture = false;
                }
                break;
            }
        }

        // Always forward to WebView so ordinary interaction is untouched.
        return super.onTouchEvent(event);
    }

    private void resetGesture() {
        downX = -1f;
        downY = -1f;
        downTime = 0L;
        maybeGesture = false;
    }
}
