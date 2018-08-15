/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.material.snackbar;

import com.google.android.material.R;

import static androidx.annotation.RestrictTo.Scope.LIBRARY_GROUP;
import static com.google.android.material.animation.AnimationUtils.FAST_OUT_SLOW_IN_INTERPOLATOR;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.TypedArray;
import android.os.Build;
import android.os.Build.VERSION_CODES;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import androidx.annotation.IntDef;
import androidx.annotation.IntRange;
import androidx.annotation.LayoutRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RestrictTo;
import com.google.android.material.behavior.SwipeDismissBehavior;
import com.google.android.material.internal.ThemeEnforcement;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.core.view.AccessibilityDelegateCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.view.ViewGroup.MarginLayoutParams;
import android.view.ViewParent;
import android.view.accessibility.AccessibilityManager;
import android.widget.FrameLayout;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;

/**
 * Base class for lightweight transient bars that are displayed along the bottom edge of the
 * application window.
 *
 * @param <B> The transient bottom bar subclass.
 */
public abstract class CustomBaseTransientBottomBar<B extends CustomBaseTransientBottomBar<B>> {
  /**
   * Base class for {@link CustomBaseTransientBottomBar} callbacks.
   *
   * @param <B> The transient bottom bar subclass.
   * @see CustomBaseTransientBottomBar#addCallback(BaseCallback)
   */
  public abstract static class BaseCallback<B> {
    /** Indicates that the Snackbar was dismissed via a swipe. */
    public static final int DISMISS_EVENT_SWIPE = 0;
    /** Indicates that the Snackbar was dismissed via an action click. */
    public static final int DISMISS_EVENT_ACTION = 1;
    /** Indicates that the Snackbar was dismissed via a timeout. */
    public static final int DISMISS_EVENT_TIMEOUT = 2;
    /** Indicates that the Snackbar was dismissed via a call to {@link #dismiss()}. */
    public static final int DISMISS_EVENT_MANUAL = 3;
    /** Indicates that the Snackbar was dismissed from a new Snackbar being shown. */
    public static final int DISMISS_EVENT_CONSECUTIVE = 4;

    /** @hide */
    @RestrictTo(LIBRARY_GROUP)
    @IntDef({
      DISMISS_EVENT_SWIPE,
      DISMISS_EVENT_ACTION,
      DISMISS_EVENT_TIMEOUT,
      DISMISS_EVENT_MANUAL,
      DISMISS_EVENT_CONSECUTIVE
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface DismissEvent {}

    /**
     * Called when the given {@link CustomBaseTransientBottomBar} has been dismissed, either through a
     * time-out, having been manually dismissed, or an action being clicked.
     *
     * @param transientBottomBar The transient bottom bar which has been dismissed.
     * @param event The event which caused the dismissal. One of either: {@link
     *     #DISMISS_EVENT_SWIPE}, {@link #DISMISS_EVENT_ACTION}, {@link #DISMISS_EVENT_TIMEOUT},
     *     {@link #DISMISS_EVENT_MANUAL} or {@link #DISMISS_EVENT_CONSECUTIVE}.
     * @see CustomBaseTransientBottomBar#dismiss()
     */
    public void onDismissed(B transientBottomBar, @DismissEvent int event) {
      // empty
    }

    /**
     * Called when the given {@link CustomBaseTransientBottomBar} is visible.
     *
     * @param transientBottomBar The transient bottom bar which is now visible.
     * @see CustomBaseTransientBottomBar#show()
     */
    public void onShown(B transientBottomBar) {
      // empty
    }
  }

  /**
   * Interface that defines the behavior of the main content of a transient bottom bar.
   *
   * @deprecated Use {@link com.google.android.material.snackbar.ContentViewCallback} instead.
   */
  @Deprecated
  public interface ContentViewCallback
      extends com.google.android.material.snackbar.ContentViewCallback {}

  /** @hide */
  @RestrictTo(LIBRARY_GROUP)
  @IntDef({LENGTH_INDEFINITE, LENGTH_SHORT, LENGTH_LONG})
  @IntRange(from = 1)
  @Retention(RetentionPolicy.SOURCE)
  public @interface Duration {}

  /**
   * Show the Snackbar indefinitely. This means that the Snackbar will be displayed from the time
   * that is {@link #show() shown} until either it is dismissed, or another Snackbar is shown.
   *
   * @see #setDuration
   */
  public static final int LENGTH_INDEFINITE = -2;

  /**
   * Show the Snackbar for a short period of time.
   *
   * @see #setDuration
   */
  public static final int LENGTH_SHORT = -1;

  /**
   * Show the Snackbar for a long period of time.
   *
   * @see #setDuration
   */
  public static final int LENGTH_LONG = 0;

  static final int ANIMATION_DURATION = 250;
  static final int ANIMATION_FADE_DURATION = 180;

  static final Handler handler;
  static final int MSG_SHOW = 0;
  static final int MSG_DISMISS = 1;

  // On JB/KK versions of the platform sometimes View.setTranslationY does not result in
  // layout / draw pass, and CoordinatorLayout relies on a draw pass to happen to sync vertical
  // positioning of all its child views
  private static final boolean USE_OFFSET_API =
      (Build.VERSION.SDK_INT >= VERSION_CODES.JELLY_BEAN)
          && (Build.VERSION.SDK_INT <= VERSION_CODES.KITKAT);

  private static final int[] SNACKBAR_STYLE_ATTR = new int[] {R.attr.snackbarStyle};

  static {
    handler =
        new Handler(
            Looper.getMainLooper(),
            new Handler.Callback() {
              @Override
              public boolean handleMessage(Message message) {
                switch (message.what) {
                  case MSG_SHOW:
                    ((CustomBaseTransientBottomBar) message.obj).showView();
                    return true;
                  case MSG_DISMISS:
                    ((CustomBaseTransientBottomBar) message.obj).hideView(message.arg1);
                    return true;
                  default:
                    return false;
                }
              }
            });
  }

  private final ViewGroup targetParent;
  private final Context context;
  protected final SnackbarBaseLayout view;
  private final com.google.android.material.snackbar.ContentViewCallback contentViewCallback;
  private int duration;

  private List<BaseCallback<B>> callbacks;

  private CustomBaseTransientBottomBar.Behavior behavior;

  private final AccessibilityManager accessibilityManager;

  /** @hide */
  // TODO: make package private after the widget migration is finished
  @RestrictTo(LIBRARY_GROUP)
  protected interface OnLayoutChangeListener {
    void onLayoutChange(View view, int left, int top, int right, int bottom);
  }

  /** @hide */
  // TODO: make package private after the widget migration is finished
  @RestrictTo(LIBRARY_GROUP)
  protected interface OnAttachStateChangeListener {
    void onViewAttachedToWindow(View v);

    void onViewDetachedFromWindow(View v);
  }

  /**
   * Constructor for the transient bottom bar.
   *
   * @param parent The parent for this transient bottom bar.
   * @param content The content view for this transient bottom bar.
   * @param contentViewCallback The content view callback for this transient bottom bar.
   */
  protected CustomBaseTransientBottomBar(
      @NonNull ViewGroup parent,
      @NonNull View content,
      @NonNull com.google.android.material.snackbar.ContentViewCallback contentViewCallback) {
    if (parent == null) {
      throw new IllegalArgumentException("Transient bottom bar must have non-null parent");
    }
    if (content == null) {
      throw new IllegalArgumentException("Transient bottom bar must have non-null content");
    }
    if (contentViewCallback == null) {
      throw new IllegalArgumentException("Transient bottom bar must have non-null callback");
    }

    targetParent = parent;
    this.contentViewCallback = contentViewCallback;
    context = parent.getContext();

    ThemeEnforcement.checkAppCompatTheme(context);

    LayoutInflater inflater = LayoutInflater.from(context);
    // Note that for backwards compatibility reasons we inflate a layout that is defined
    // in the extending Snackbar class. This is to prevent breakage of apps that have custom
    // coordinator layout behaviors that depend on that layout.
    view = (SnackbarBaseLayout) inflater.inflate(getSnackbarBaseLayoutResId(), targetParent, false);
    view.addView(content);

    ViewCompat.setAccessibilityLiveRegion(view, ViewCompat.ACCESSIBILITY_LIVE_REGION_POLITE);
    ViewCompat.setImportantForAccessibility(view, ViewCompat.IMPORTANT_FOR_ACCESSIBILITY_YES);

    // Make sure that we fit system windows and have a listener to apply any insets
    ViewCompat.setFitsSystemWindows(view, true);
    ViewCompat.setOnApplyWindowInsetsListener(
        view,
        new androidx.core.view.OnApplyWindowInsetsListener() {
          @Override
          public WindowInsetsCompat onApplyWindowInsets(View v, WindowInsetsCompat insets) {
            // Copy over the bottom inset as padding so that we're displayed
            // above the navigation bar
            v.setPadding(
                v.getPaddingLeft(),
                v.getPaddingTop(),
                v.getPaddingRight(),
                insets.getSystemWindowInsetBottom());
            return insets;
          }
        });

    // Handle accessibility events
    ViewCompat.setAccessibilityDelegate(
        view,
        new AccessibilityDelegateCompat() {
          @Override
          public void onInitializeAccessibilityNodeInfo(
              View host, AccessibilityNodeInfoCompat info) {
            super.onInitializeAccessibilityNodeInfo(host, info);
            info.addAction(AccessibilityNodeInfoCompat.ACTION_DISMISS);
            info.setDismissable(true);
          }

          @Override
          public boolean performAccessibilityAction(View host, int action, Bundle args) {
            if (action == AccessibilityNodeInfoCompat.ACTION_DISMISS) {
              dismiss();
              return true;
            }
            return super.performAccessibilityAction(host, action, args);
          }
        });

    accessibilityManager =
        (AccessibilityManager) context.getSystemService(Context.ACCESSIBILITY_SERVICE);
  }

  @LayoutRes
  protected int getSnackbarBaseLayoutResId() {
    return hasSnackbarStyleAttr() ? R.layout.mtrl_layout_snackbar : R.layout.design_layout_snackbar;
  }

  /**
   * {@link Snackbar}s should still work with AppCompat themes, which don't specify a {@code
   * snackbarStyle}. This method helps to check if a valid {@code snackbarStyle} is set within the
   * current context, so that we know whether we can use the attribute.
   */
  protected boolean hasSnackbarStyleAttr() {
    TypedArray a = context.obtainStyledAttributes(SNACKBAR_STYLE_ATTR);
    int snackbarStyleResId = a.getResourceId(0, -1);
    a.recycle();
    return snackbarStyleResId != -1;
  }

  /**
   * Set how long to show the view for.
   *
   * @param duration How long to display the message. Can be {@link #LENGTH_SHORT}, {@link
   *     #LENGTH_LONG}, {@link #LENGTH_INDEFINITE}, or a custom duration in milliseconds.
   */
  @NonNull
  public B setDuration(@Duration int duration) {
    this.duration = duration;
    return (B) this;
  }

  /**
   * Return the duration.
   *
   * @see #setDuration
   */
  @Duration
  public int getDuration() {
    return duration;
  }

  /**
   * Sets the {@link CustomBaseTransientBottomBar.Behavior} to be used in this {@link
   * CustomBaseTransientBottomBar}.
   *
   * @param behavior {@link CustomBaseTransientBottomBar.Behavior} to be applied.
   */
  public B setBehavior(CustomBaseTransientBottomBar.Behavior behavior) {
    this.behavior = behavior;
    return (B) this;
  }

  /**
   * Return the behavior.
   *
   * @see #setBehavior(CustomBaseTransientBottomBar.Behavior)
   */
  public CustomBaseTransientBottomBar.Behavior getBehavior() {
    return behavior;
  }

  /** Returns the {@link CustomBaseTransientBottomBar}'s context. */
  @NonNull
  public Context getContext() {
    return context;
  }

  /** Returns the {@link CustomBaseTransientBottomBar}'s view. */
  @NonNull
  public View getView() {
    return view;
  }

  /** Show the {@link CustomBaseTransientBottomBar}. */
  public void show() {
    SnackbarManager.getInstance().show(getDuration(), managerCallback);
  }

  /** Dismiss the {@link CustomBaseTransientBottomBar}. */
  public void dismiss() {
    dispatchDismiss(BaseCallback.DISMISS_EVENT_MANUAL);
  }

  protected void dispatchDismiss(@BaseCallback.DismissEvent int event) {
    SnackbarManager.getInstance().dismiss(managerCallback, event);
  }

  /**
   * Adds the specified callback to the list of callbacks that will be notified of transient bottom
   * bar events.
   *
   * @param callback Callback to notify when transient bottom bar events occur.
   * @see #removeCallback(BaseCallback)
   */
  @NonNull
  public B addCallback(@NonNull BaseCallback<B> callback) {
    if (callback == null) {
      return (B) this;
    }
    if (callbacks == null) {
      callbacks = new ArrayList<BaseCallback<B>>();
    }
    callbacks.add(callback);
    return (B) this;
  }

  /**
   * Removes the specified callback from the list of callbacks that will be notified of transient
   * bottom bar events.
   *
   * @param callback Callback to remove from being notified of transient bottom bar events
   * @see #addCallback(BaseCallback)
   */
  @NonNull
  public B removeCallback(@NonNull BaseCallback<B> callback) {
    if (callback == null) {
      return (B) this;
    }
    if (callbacks == null) {
      // This can happen if this method is called before the first call to addCallback
      return (B) this;
    }
    callbacks.remove(callback);
    return (B) this;
  }

  /** Return whether this {@link CustomBaseTransientBottomBar} is currently being shown. */
  public boolean isShown() {
    return SnackbarManager.getInstance().isCurrent(managerCallback);
  }

  /**
   * Returns whether this {@link CustomBaseTransientBottomBar} is currently being shown, or is queued to
   * be shown next.
   */
  public boolean isShownOrQueued() {
    return SnackbarManager.getInstance().isCurrentOrNext(managerCallback);
  }

  final SnackbarManager.Callback managerCallback =
      new SnackbarManager.Callback() {
        @Override
        public void show() {
          handler.sendMessage(handler.obtainMessage(MSG_SHOW, CustomBaseTransientBottomBar.this));
        }

        @Override
        public void dismiss(int event) {
          handler.sendMessage(
              handler.obtainMessage(MSG_DISMISS, event, 0, CustomBaseTransientBottomBar.this));
        }
      };

  protected SwipeDismissBehavior<? extends View> getNewBehavior() {
    return new Behavior();
  }

  final void showView() {
    if (this.view.getParent() == null) {
      final ViewGroup.LayoutParams lp = this.view.getLayoutParams();

      if (lp instanceof CoordinatorLayout.LayoutParams) {
        // If our LayoutParams are from a CoordinatorLayout, we'll setup our Behavior
        final CoordinatorLayout.LayoutParams clp = (CoordinatorLayout.LayoutParams) lp;

        final SwipeDismissBehavior<? extends View> behavior =
            this.behavior == null ? getNewBehavior() : this.behavior;

        if (behavior instanceof CustomBaseTransientBottomBar.Behavior) {
          ((CustomBaseTransientBottomBar.Behavior) behavior).setCustomBaseTransientBottomBar(this);
        }
        behavior.setListener(
            new SwipeDismissBehavior.OnDismissListener() {
              @Override
              public void onDismiss(View view) {
                view.setVisibility(View.GONE);
                dispatchDismiss(BaseCallback.DISMISS_EVENT_SWIPE);
              }

              @Override
              public void onDragStateChanged(int state) {
                switch (state) {
                  case SwipeDismissBehavior.STATE_DRAGGING:
                  case SwipeDismissBehavior.STATE_SETTLING:
                    // If the view is being dragged or settling, pause the timeout
                    SnackbarManager.getInstance().pauseTimeout(managerCallback);
                    break;
                  case SwipeDismissBehavior.STATE_IDLE:
                    // If the view has been released and is idle, restore the timeout
                    SnackbarManager.getInstance().restoreTimeoutIfPaused(managerCallback);
                    break;
                  default:
                    // Any other state is ignored
                }
              }
            });
        clp.setBehavior(behavior);
        // Also set the inset edge so that views can dodge the bar correctly
        clp.insetEdge = Gravity.BOTTOM;
      }

      targetParent.addView(this.view);
    }

    this.view.setOnAttachStateChangeListener(
        new CustomBaseTransientBottomBar.OnAttachStateChangeListener() {
          @Override
          public void onViewAttachedToWindow(View v) {}

          @Override
          public void onViewDetachedFromWindow(View v) {
            if (isShownOrQueued()) {
              // If we haven't already been dismissed then this event is coming from a
              // non-user initiated action. Hence we need to make sure that we callback
              // and keep our state up to date. We need to post the call since
              // removeView() will call through to onDetachedFromWindow and thus overflow.
              handler.post(
                  new Runnable() {
                    @Override
                    public void run() {
                      onViewHidden(BaseCallback.DISMISS_EVENT_MANUAL);
                    }
                  });
            }
          }
        });

    if (ViewCompat.isLaidOut(this.view)) {
      if (shouldAnimate()) {
        // If animations are enabled, animate it in
        animateViewIn();
      } else {
        // Else if anims are disabled just call back now
        onViewShown();
      }
    } else {
      // Otherwise, add one of our layout change listeners and show it in when laid out
      this.view.setOnLayoutChangeListener(
          new CustomBaseTransientBottomBar.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View view, int left, int top, int right, int bottom) {
              CustomBaseTransientBottomBar.this.view.setOnLayoutChangeListener(null);

              if (shouldAnimate()) {
                // If animations are enabled, animate it in
                animateViewIn();
              } else {
                // Else if anims are disabled just call back now
                onViewShown();
              }
            }
          });
    }
  }

  void animateViewIn() {
    final int translationYBottom = getTranslationYBottom();
    if (USE_OFFSET_API) {
      ViewCompat.offsetTopAndBottom(view, translationYBottom);
    } else {
      view.setTranslationY(translationYBottom);
    }

    final ValueAnimator animator = new ValueAnimator();
    animator.setIntValues(translationYBottom, 0);
    animator.setInterpolator(FAST_OUT_SLOW_IN_INTERPOLATOR);
    animator.setDuration(ANIMATION_DURATION);
    animator.addListener(
        new AnimatorListenerAdapter() {
          @Override
          public void onAnimationStart(Animator animator) {
            contentViewCallback.animateContentIn(
                ANIMATION_DURATION - ANIMATION_FADE_DURATION, ANIMATION_FADE_DURATION);
          }

          @Override
          public void onAnimationEnd(Animator animator) {
            onViewShown();
          }
        });
    animator.addUpdateListener(
        new ValueAnimator.AnimatorUpdateListener() {
          private int previousAnimatedIntValue = translationYBottom;

          @Override
          public void onAnimationUpdate(ValueAnimator animator) {
            int currentAnimatedIntValue = (int) animator.getAnimatedValue();
            if (USE_OFFSET_API) {
              // On JB/KK versions of the platform sometimes View.setTranslationY does not
              // result in layout / draw pass
              ViewCompat.offsetTopAndBottom(
                  view, currentAnimatedIntValue - previousAnimatedIntValue);
            } else {
              view.setTranslationY(currentAnimatedIntValue);
            }
            previousAnimatedIntValue = currentAnimatedIntValue;
          }
        });
    animator.start();
  }

  private void animateViewOut(final int event) {
    final ValueAnimator animator = new ValueAnimator();
    animator.setIntValues(0, getTranslationYBottom());
    animator.setInterpolator(FAST_OUT_SLOW_IN_INTERPOLATOR);
    animator.setDuration(ANIMATION_DURATION);
    animator.addListener(
        new AnimatorListenerAdapter() {
          @Override
          public void onAnimationStart(Animator animator) {
            contentViewCallback.animateContentOut(0, ANIMATION_FADE_DURATION);
          }

          @Override
          public void onAnimationEnd(Animator animator) {
            onViewHidden(event);
          }
        });
    animator.addUpdateListener(
        new ValueAnimator.AnimatorUpdateListener() {
          private int previousAnimatedIntValue = 0;

          @Override
          public void onAnimationUpdate(ValueAnimator animator) {
            int currentAnimatedIntValue = (int) animator.getAnimatedValue();
            if (USE_OFFSET_API) {
              // On JB/KK versions of the platform sometimes View.setTranslationY does not
              // result in layout / draw pass
              ViewCompat.offsetTopAndBottom(
                  view, currentAnimatedIntValue - previousAnimatedIntValue);
            } else {
              view.setTranslationY(currentAnimatedIntValue);
            }
            previousAnimatedIntValue = currentAnimatedIntValue;
          }
        });
    animator.start();
  }

  private int getTranslationYBottom() {
    int translationY = view.getHeight();
    LayoutParams layoutParams = view.getLayoutParams();
    if (layoutParams instanceof MarginLayoutParams) {
      translationY += ((MarginLayoutParams) layoutParams).bottomMargin;
    }
    return translationY;
  }

  final void hideView(@BaseCallback.DismissEvent final int event) {
    if (shouldAnimate() && view.getVisibility() == View.VISIBLE) {
      animateViewOut(event);
    } else {
      // If anims are disabled or the view isn't visible, just call back now
      onViewHidden(event);
    }
  }

  void onViewShown() {
    SnackbarManager.getInstance().onShown(managerCallback);
    if (callbacks != null) {
      // Notify the callbacks. Do that from the end of the list so that if a callback
      // removes itself as the result of being called, it won't mess up with our iteration
      int callbackCount = callbacks.size();
      for (int i = callbackCount - 1; i >= 0; i--) {
        callbacks.get(i).onShown((B) this);
      }
    }
  }

  void onViewHidden(int event) {
    // First tell the SnackbarManager that it has been dismissed
    SnackbarManager.getInstance().onDismissed(managerCallback);
    if (callbacks != null) {
      // Notify the callbacks. Do that from the end of the list so that if a callback
      // removes itself as the result of being called, it won't mess up with our iteration
      int callbackCount = callbacks.size();
      for (int i = callbackCount - 1; i >= 0; i--) {
        callbacks.get(i).onDismissed((B) this, event);
      }
    }
    // Lastly, hide and remove the view from the parent (if attached)
    final ViewParent parent = view.getParent();
    if (parent instanceof ViewGroup) {
      ((ViewGroup) parent).removeView(view);
    }
  }

  /** Returns true if we should animate the Snackbar view in/out. */
  boolean shouldAnimate() {
    return true;
    /**
    final int feedbackFlags = AccessibilityServiceInfo.FEEDBACK_SPOKEN;
    List<AccessibilityServiceInfo> serviceList =
        accessibilityManager.getEnabledAccessibilityServiceList(feedbackFlags);
    return serviceList != null && serviceList.isEmpty();
    */
  }

  /** @hide */
  @RestrictTo(LIBRARY_GROUP)
  protected static class SnackbarBaseLayout extends FrameLayout {

    private static final OnTouchListener consumeAllTouchListener =
        new OnTouchListener() {
          @SuppressLint("ClickableViewAccessibility")
          @Override
          public boolean onTouch(View v, MotionEvent event) {
            // Prevent touches from passing through this view.
            return true;
          }
        };

    private CustomBaseTransientBottomBar.OnLayoutChangeListener onLayoutChangeListener;
    private CustomBaseTransientBottomBar.OnAttachStateChangeListener onAttachStateChangeListener;

    protected SnackbarBaseLayout(Context context) {
      this(context, null);
    }

    protected SnackbarBaseLayout(Context context, AttributeSet attrs) {
      super(context, attrs);
      TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.SnackbarLayout);
      if (a.hasValue(R.styleable.SnackbarLayout_elevation)) {
        ViewCompat.setElevation(
            this, a.getDimensionPixelSize(R.styleable.SnackbarLayout_elevation, 0));
      }
      a.recycle();

      setOnTouchListener(consumeAllTouchListener);
      setFocusable(true);
    }

    @Override
    public void setOnClickListener(@Nullable OnClickListener onClickListener) {
      // Clear touch listener that consumes all touches if there is a custom click listener.
      setOnTouchListener(onClickListener != null ? null : consumeAllTouchListener);
      super.setOnClickListener(onClickListener);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
      super.onLayout(changed, l, t, r, b);
      if (onLayoutChangeListener != null) {
        onLayoutChangeListener.onLayoutChange(this, l, t, r, b);
      }
    }

    @Override
    protected void onAttachedToWindow() {
      super.onAttachedToWindow();
      if (onAttachStateChangeListener != null) {
        onAttachStateChangeListener.onViewAttachedToWindow(this);
      }

      ViewCompat.requestApplyInsets(this);
    }

    @Override
    protected void onDetachedFromWindow() {
      super.onDetachedFromWindow();
      if (onAttachStateChangeListener != null) {
        onAttachStateChangeListener.onViewDetachedFromWindow(this);
      }
    }

    void setOnLayoutChangeListener(
        CustomBaseTransientBottomBar.OnLayoutChangeListener onLayoutChangeListener) {
      this.onLayoutChangeListener = onLayoutChangeListener;
    }

    void setOnAttachStateChangeListener(
        CustomBaseTransientBottomBar.OnAttachStateChangeListener listener) {
      onAttachStateChangeListener = listener;
    }
  }

  /** Behavior for {@link CustomBaseTransientBottomBar}. */
  public static class Behavior extends SwipeDismissBehavior<View> {
    private final BehaviorDelegate delegate;

    public Behavior() {
      delegate = new BehaviorDelegate(this);
    }

    private void setCustomBaseTransientBottomBar(CustomBaseTransientBottomBar<?> baseTransientBottomBar) {
      delegate.setCustomBaseTransientBottomBar(baseTransientBottomBar);
    }

    @Override
    public boolean canSwipeDismissView(View child) {
      return delegate.canSwipeDismissView(child);
    }

    @Override
    public boolean onInterceptTouchEvent(CoordinatorLayout parent, View child, MotionEvent event) {
      delegate.onInterceptTouchEvent(parent, child, event);
      return super.onInterceptTouchEvent(parent, child, event);
    }
  }

  /** @hide */
  @RestrictTo(LIBRARY_GROUP)
  // TODO: Delegate can be rolled up into behavior after the widget migration is finished.
  public static class BehaviorDelegate {
    private SnackbarManager.Callback managerCallback;

    public BehaviorDelegate(SwipeDismissBehavior<?> behavior) {
      behavior.setStartAlphaSwipeDistance(0.1f);
      behavior.setEndAlphaSwipeDistance(0.6f);
      behavior.setSwipeDirection(SwipeDismissBehavior.SWIPE_DIRECTION_START_TO_END);
    }

    public void setCustomBaseTransientBottomBar(CustomBaseTransientBottomBar<?> baseTransientBottomBar) {
      this.managerCallback = baseTransientBottomBar.managerCallback;
    }

    public boolean canSwipeDismissView(View child) {
      return child instanceof SnackbarBaseLayout;
    }

    public void onInterceptTouchEvent(CoordinatorLayout parent, View child, MotionEvent event) {
      switch (event.getActionMasked()) {
        case MotionEvent.ACTION_DOWN:
          // We want to make sure that we disable any Snackbar timeouts if the user is
          // currently touching the Snackbar. We restore the timeout when complete
          if (parent.isPointInChildBounds(child, (int) event.getX(), (int) event.getY())) {
            SnackbarManager.getInstance().pauseTimeout(managerCallback);
          }
          break;
        case MotionEvent.ACTION_UP:
        case MotionEvent.ACTION_CANCEL:
          SnackbarManager.getInstance().restoreTimeoutIfPaused(managerCallback);
          break;
        default:
          break;
      }
    }
  }
}