package com.proggerx.dcross2000.view;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewParent;
import android.view.animation.DecelerateInterpolator;

import java.util.Locale;

import com.google.android.material.color.MaterialColors;
import com.proggerx.dcross2000.R;

/**
 * Horizontal snapping wheel for millisecond values.
 *
 * Unlike a seek bar the value moves in deliberate steps and snaps to them, so
 * a slightly unsteady finger cannot change a transition by tens of
 * milliseconds. Drag or flick horizontally; the focused value sits in the
 * middle of the wheel.
 */
public final class MixWheelView extends View {
	private static final int VISIBLE_STEPS = 4;

	private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint focusPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint valuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final RectF rect = new RectF();
	private final float density;
	private final float scaledDensity;
	private final float stepPx;
	private int onSurfaceColor;

	private int minValue;
	private int maxValue = 60000;
	private int step = 100;
	private int value;
	private float lastX;
	private float dragRemainder;
	private VelocityTracker velocityTracker;
	private ValueAnimator animator;
	private OnValueChangedListener listener;

	public interface OnValueChangedListener {
		void onValueChanged(int value);
	}

	public MixWheelView(Context context, AttributeSet attrs) {
		super(context, attrs);
		density = getResources().getDisplayMetrics().density;
		scaledDensity = getResources().getDisplayMetrics().scaledDensity;
		stepPx = 34f * density;

		int onSurface = MaterialColors.getColor(this, R.attr.colorOnSurface, Color.WHITE);
		onSurfaceColor = onSurface;
		int surface = resolveColor(R.attr.colorSurfaceContainerHigh,
				resolveColor(R.attr.colorSurfaceVariant, Color.DKGRAY));
		int primary = MaterialColors.getColor(this, R.attr.colorPrimary, Color.WHITE);

		backgroundPaint.setStyle(Paint.Style.FILL);
		backgroundPaint.setColor(surface);
		focusPaint.setStyle(Paint.Style.FILL);
		focusPaint.setColor(alpha(primary, 0x2E));
		tickPaint.setStrokeWidth(Math.max(1f, density));
		tickPaint.setColor(alpha(onSurface, 0x33));
		valuePaint.setStyle(Paint.Style.FILL);
		valuePaint.setColor(onSurface);
		valuePaint.setTextAlign(Paint.Align.CENTER);
		setFocusable(true);
		setContentDescription("Wheel");
	}

	private int resolveColor(int attr, int fallback) {
		try {
			return MaterialColors.getColor(this, attr, fallback);
		} catch(Exception error) {
			return fallback;
		}
	}

	public void setRange(int minValue, int maxValue, int step) {
		this.minValue = Math.max(0, minValue);
		this.maxValue = Math.max(this.minValue, maxValue);
		this.step = Math.max(1, step);
		setValue(value);
	}

	public void setOnValueChangedListener(OnValueChangedListener listener) {
		this.listener = listener;
	}

	/** Sets the value without notifying the listener. */
	public void setValue(int value) {
		int snapped = snap(value);
		if(snapped == this.value) {
			invalidate();
			return;
		}
		this.value = snapped;
		invalidate();
	}

	public int getValue() {
		return value;
	}

	private int snap(int value) {
		int snapped = Math.round(value / (float) step) * step;
		snapped = Math.max(minValue, Math.min(maxValue, snapped));
		return snapped;
	}

	private void changeBy(int steps, boolean haptic) {
		int next = snap(value + steps * step);
		if(next == value) return;
		value = next;
		if(haptic) {
			performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
		}
		invalidate();
		if(listener != null) {
			listener.onValueChanged(value);
		}
	}

	@Override
	protected void onDraw(Canvas canvas) {
		super.onDraw(canvas);
		int width = getWidth();
		int height = getHeight();
		if(width <= 0 || height <= 0) return;

		float centerX = width / 2f;
		float centerY = height / 2f;
		rect.set(0, 0, width, height);
		canvas.drawRoundRect(rect, height / 2f, height / 2f, backgroundPaint);

		float focusWidth = Math.min(width * 0.28f, 96f * density);
		float focusHeight = height * 0.62f;
		rect.set(centerX - focusWidth / 2f, centerY - focusHeight / 2f,
				centerX + focusWidth / 2f, centerY + focusHeight / 2f);
		canvas.drawRoundRect(rect, 14f * density, 14f * density, focusPaint);

		for(int i = -VISIBLE_STEPS - 1; i <= VISIBLE_STEPS + 1; i++) {
			float x = centerX + i * stepPx;
			if(x < 0 || x > width) continue;

			if(i == 0) continue;
			canvas.drawLine(x, centerY - 5f * density, x, centerY + 5f * density, tickPaint);

			int stepsFromCenter = i;
			int itemValue = value + stepsFromCenter * step;
			if(itemValue < minValue || itemValue > maxValue) continue;
			if(Math.abs(stepsFromCenter) > VISIBLE_STEPS) continue;

			int alpha = Math.abs(stepsFromCenter) == 1 ? 0xAA
					: Math.abs(stepsFromCenter) == 2 ? 0x70 : 0x44;
			valuePaint.setColor(alpha(onSurfaceColor, alpha));
			valuePaint.setTextSize(12f * scaledDensity);
			drawText(canvas, seconds(itemValue), x, centerY);
		}

		valuePaint.setColor(onSurfaceColor);
		valuePaint.setTextSize(19f * scaledDensity);
		valuePaint.setFakeBoldText(true);
		drawText(canvas, seconds(value), centerX, centerY);
		valuePaint.setFakeBoldText(false);
	}

	private void drawText(Canvas canvas, String text, float x, float centerY) {
		Paint.FontMetrics metrics = valuePaint.getFontMetrics();
		float baseline = centerY - (metrics.ascent + metrics.descent) / 2f;
		canvas.drawText(text, x, baseline, valuePaint);
	}

	private static String seconds(int millis) {
		return String.format(Locale.US, "%.1f", millis / 1000f);
	}

	@Override
	public boolean onTouchEvent(MotionEvent event) {
		if(!isEnabled()) return false;
		switch(event.getActionMasked()) {
			case MotionEvent.ACTION_DOWN:
				if(animator != null) {
					animator.cancel();
					animator = null;
				}
				lastX = event.getX();
				dragRemainder = 0f;
				if(velocityTracker == null) velocityTracker = VelocityTracker.obtain();
				velocityTracker.clear();
				velocityTracker.addMovement(event);
				ViewParent parent = getParent();
				if(parent != null) parent.requestDisallowInterceptTouchEvent(true);
				return true;
			case MotionEvent.ACTION_MOVE:
				float x = event.getX();
				dragRemainder += lastX - x;
				lastX = x;
				int steps = (int) (dragRemainder / stepPx);
				if(steps != 0) {
					dragRemainder -= steps * stepPx;
					changeBy(steps, true);
				}
				if(velocityTracker != null) velocityTracker.addMovement(event);
				return true;
			case MotionEvent.ACTION_UP:
			case MotionEvent.ACTION_CANCEL:
				ViewParent container = getParent();
				if(container != null) container.requestDisallowInterceptTouchEvent(false);
				if(event.getActionMasked() == MotionEvent.ACTION_UP) {
					fling(event);
					performClick();
				}
				if(velocityTracker != null) {
					velocityTracker.recycle();
					velocityTracker = null;
				}
				return true;
			default:
				return super.onTouchEvent(event);
		}
	}

	private void fling(MotionEvent event) {
		if(velocityTracker == null) return;
		velocityTracker.addMovement(event);
		velocityTracker.computeCurrentVelocity(1000);
		float velocity = velocityTracker.getXVelocity();
		int projected = Math.round(-velocity / stepPx * 0.11f);
		projected = Math.max(-30, Math.min(30, projected));
		if(Math.abs(projected) < 2) return;

		int target = snap(value + projected * step);
		if(target == value) return;
		if(animator != null) animator.cancel();
		animator = ValueAnimator.ofInt(value, target);
		animator.setDuration(220L);
		animator.setInterpolator(new DecelerateInterpolator());
		animator.addUpdateListener(animation -> {
			int next = (Integer) animation.getAnimatedValue();
			if(next != value) {
				value = next;
				invalidate();
				if(listener != null) listener.onValueChanged(value);
			}
		});
		animator.start();
	}

	@Override
	public boolean performClick() {
		return super.performClick();
	}

	@Override
	protected void onDetachedFromWindow() {
		if(animator != null) {
			animator.cancel();
			animator = null;
		}
		super.onDetachedFromWindow();
	}

	private static int alpha(int color, int alpha) {
		return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
	}
}
