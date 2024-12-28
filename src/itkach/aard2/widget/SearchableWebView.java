/*
 * This file is heavily inspired by the Android Open Source Project
 * licensed under the Apache License, Version 2.0
 */

package itkach.aard2.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.webkit.WebView;

import androidx.annotation.Nullable;
import androidx.core.view.NestedScrollingChild;
import androidx.core.view.NestedScrollingChildHelper;
import androidx.core.view.ViewCompat;

class SearchableWebView extends WebView implements NestedScrollingChild {

    private String mLastFind = null;

    private int lastMotionX = 0;
    private int lastMotionY = 0;

    private int[] scrollOffset = new int[2];
    private int[] scrollConsumed = new int[2];

    private int nestedOffsetY = 0;

    private NestedScrollingChildHelper childHelper = new NestedScrollingChildHelper(this);

    public void setLastFind(String find) {
        mLastFind = find;
    }

    public SearchableWebView(Context context) {
        this(context, null);
    }

    public SearchableWebView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    private void init() {
        setNestedScrollingEnabled(true);
    }

    /**
     * Start an ActionMode for finding text in this WebView.  Only works if this
     * WebView is attached to the view system.
     *
     * @param text    If non-null, will be the initial text to search for.
     *                Otherwise, the last String searched for in this WebView will
     *                be used to start.
     * @param showIme If true, show the IME, assuming the user will begin typing.
     *                If false and text is non-null, perform a find all.
     * @return boolean True if the find dialog is shown, false otherwise.
     */
    @Override
    public boolean showFindDialog(String text, boolean showIme) {
        FindActionModeCallback callback = new FindActionModeCallback(getContext(), this);
        if (getParent() == null || startActionMode(callback) == null) {
            // Could not start the action mode, so end Find on page
            return false;
        }

        if (showIme) {
            callback.showSoftInput();
        } else if (text != null) {
            callback.setText(text);
            callback.findAll();
            return true;
        }
        if (text == null) {
            text = mLastFind;
        }
        if (text != null) {
            callback.setText(text);
            callback.findAll();
        }
        return true;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        boolean result = false;

        MotionEvent trackedEvent = MotionEvent.obtain(event);

        int action = event.getActionMasked();

        if (action == MotionEvent.ACTION_DOWN) {
            nestedOffsetY = 0;
        }

        float x = event.getX();
        float y = event.getY();

        event.offsetLocation(0f, nestedOffsetY);

        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                lastMotionX = (int) x;
                lastMotionY = (int) y;
                startNestedScroll(ViewCompat.SCROLL_AXIS_VERTICAL);
                result = super.onTouchEvent(event);
                break;
            }
            case MotionEvent.ACTION_MOVE : {
                int deltaY = (int) (lastMotionY - y);

                if (Math.abs(deltaY) > Math.abs(lastMotionX - x) &&
                        (canScrollVertically(1) || canScrollVertically(-1))
                ) {
                    requestDisallowInterceptTouchEvent(true);
                }

                if (dispatchNestedPreScroll(0, deltaY, scrollConsumed, scrollOffset)) {
                    deltaY -= scrollConsumed[1];
                    trackedEvent.offsetLocation(0f, scrollOffset[1]);
                    nestedOffsetY += scrollOffset[1];
                }

                lastMotionY = (int) (y - scrollOffset[1]);

                int oldY = getScrollY();
                int newScrollY = Math.max(0, oldY + deltaY);
                int dyConsumed = newScrollY - oldY;
                int dyUnconsumed = deltaY - dyConsumed;

                if (dispatchNestedScroll(0, dyConsumed, 0, dyUnconsumed, scrollOffset)) {
                    lastMotionY -= scrollOffset[1];
                    trackedEvent.offsetLocation(0f, scrollOffset[1]);
                    nestedOffsetY += scrollOffset[1];
                }

                result = super.onTouchEvent(trackedEvent);
                trackedEvent.recycle();
                break;
            }
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                stopNestedScroll();
                requestDisallowInterceptTouchEvent(false);
                result = super.onTouchEvent(event);
                break;
            }
        }
        return result;
    }

    @Override
    public void setNestedScrollingEnabled(boolean enabled) {
        childHelper.setNestedScrollingEnabled(enabled);
    }

    @Override
    public void stopNestedScroll() {
        childHelper.stopNestedScroll();
    }

    @Override
    public boolean isNestedScrollingEnabled() {
        return childHelper.isNestedScrollingEnabled();
    }

    @Override
    public boolean startNestedScroll(int axes) {
        return childHelper.startNestedScroll(axes);
    }

    @Override
    public boolean hasNestedScrollingParent() {
        return childHelper.hasNestedScrollingParent();
    }

    @Override
    public boolean dispatchNestedScroll(int dxConsumed, int dyConsumed, int dxUnconsumed, int dyUnconsumed, @Nullable int[] offsetInWindow) {
        return childHelper.dispatchNestedScroll(dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed, offsetInWindow);
    }

    @Override
    public boolean dispatchNestedPreScroll(int dx, int dy, @Nullable int[] consumed, @Nullable int[] offsetInWindow) {
        return childHelper.dispatchNestedPreScroll(dx, dy, consumed, offsetInWindow);
    }

    @Override
    public boolean dispatchNestedFling(float velocityX, float velocityY, boolean consumed) {
        return childHelper.dispatchNestedFling(velocityX, velocityY, consumed);
    }

    @Override
    public boolean dispatchNestedPreFling(float velocityX, float velocityY) {
        return childHelper.dispatchNestedPreFling(velocityX, velocityY);
    }
}
