package com.termux.app.editor;

import android.content.Context;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;

import androidx.annotation.NonNull;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.termux.R;
import com.termux.app.TermuxActivity;

/**
 * Mengatur panel terminal (bottom sheet) pada layar editor Yummy.
 *
 * Tiga kondisi panel: tersembunyi, setengah layar, dan penuh.
 * Terminal baru aktif (sesi dibuat, fokus dan papan ketik diminta) ketika pengguna
 * membuka panel. Selama panel tersembunyi, terminal tidak bisa menerima fokus.
 */
public class EditorLayoutController {

    private final TermuxActivity mActivity;
    private View mSheet;
    private BottomSheetBehavior<View> mBehavior;
    private boolean mTerminalOpen = false;
    private boolean mShowKeyboardOnSettle = false;

    public EditorLayoutController(@NonNull TermuxActivity activity) {
        mActivity = activity;
    }

    /** Panggil sekali, tepat setelah setContentView(R.layout.activity_termux). */
    public void setup() {
        mSheet = mActivity.findViewById(R.id.yummy_terminal_sheet);
        if (mSheet == null) return;

        mBehavior = BottomSheetBehavior.from(mSheet);
        mBehavior.setDraggable(false);
        mBehavior.setHideable(true);
        mBehavior.setFitToContents(false);
        mBehavior.setHalfExpandedRatio(0.5f);
        mBehavior.setSkipCollapsed(true);
        mBehavior.setState(BottomSheetBehavior.STATE_HIDDEN);
        // INVISIBLE (bukan GONE): terminal tetap diukur, jadi sesi shell tetap berjalan.
        mSheet.setVisibility(View.INVISIBLE);
        // Terminal tidak boleh merebut fokus / papan ketik sebelum dibuka pengguna.
        setTerminalFocusable(false);

        mBehavior.addBottomSheetCallback(new BottomSheetBehavior.BottomSheetCallback() {
            @Override
            public void onStateChanged(@NonNull View sheet, int newState) {
                onSheetStateChanged(newState);
            }

            @Override
            public void onSlide(@NonNull View sheet, float slideOffset) {
            }
        });

        bindClick(R.id.yummy_btn_terminal, v -> toggleTerminal());
        bindClick(R.id.yummy_btn_terminal_close, v -> setSheetState(BottomSheetBehavior.STATE_HIDDEN));
        bindClick(R.id.yummy_btn_terminal_max, v -> {
            if (mBehavior.getState() == BottomSheetBehavior.STATE_EXPANDED) {
                setSheetState(BottomSheetBehavior.STATE_HALF_EXPANDED);
            } else {
                setSheetState(BottomSheetBehavior.STATE_EXPANDED);
            }
        });

        View header = mActivity.findViewById(R.id.yummy_terminal_header);
        if (header != null) {
            final GestureDetector detector = new GestureDetector(mActivity,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onDown(MotionEvent e) {
                        return true;
                    }

                    @Override
                    public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                        if (Math.abs(vy) < 400f) return false;
                        if (vy < 0) moveUp(); else moveDown();
                        return true;
                    }
                });
            header.setOnTouchListener((v, event) -> detector.onTouchEvent(event));
        }
    }

    /** True kalau panel terminal sedang dibuka (atau sedang dibuka) oleh pengguna. */
    public boolean isTerminalOpen() {
        return mTerminalOpen;
    }

    /** Dipanggil saat sesi terminal terakhir selesai: tutup panel, aplikasi tetap hidup. */
    public void onTerminalSessionsEmpty() {
        mActivity.runOnUiThread(() -> {
            if (mBehavior != null) setSheetState(BottomSheetBehavior.STATE_HIDDEN);
        });
    }

    /** Untuk tombol back: true kalau panel ditutup oleh pemanggilan ini. */
    public boolean handleBack() {
        if (mBehavior == null) return false;
        if (mBehavior.getState() == BottomSheetBehavior.STATE_HIDDEN) return false;
        setSheetState(BottomSheetBehavior.STATE_HIDDEN);
        return true;
    }

    private void toggleTerminal() {
        if (mBehavior.getState() == BottomSheetBehavior.STATE_HIDDEN) {
            setSheetState(BottomSheetBehavior.STATE_HALF_EXPANDED);
        } else {
            setSheetState(BottomSheetBehavior.STATE_HIDDEN);
        }
    }

    private void moveUp() {
        int state = mBehavior.getState();
        if (state == BottomSheetBehavior.STATE_HIDDEN) {
            setSheetState(BottomSheetBehavior.STATE_HALF_EXPANDED);
        } else if (state == BottomSheetBehavior.STATE_HALF_EXPANDED) {
            setSheetState(BottomSheetBehavior.STATE_EXPANDED);
        }
    }

    private void moveDown() {
        int state = mBehavior.getState();
        if (state == BottomSheetBehavior.STATE_EXPANDED) {
            setSheetState(BottomSheetBehavior.STATE_HALF_EXPANDED);
        } else if (state == BottomSheetBehavior.STATE_HALF_EXPANDED) {
            setSheetState(BottomSheetBehavior.STATE_HIDDEN);
        }
    }

    private void setSheetState(int state) {
        if (state != BottomSheetBehavior.STATE_HIDDEN) {
            boolean wasOpen = mTerminalOpen;
            mTerminalOpen = true;
            setTerminalFocusable(true);
            mSheet.setVisibility(View.VISIBLE);
            if (!wasOpen) {
                mShowKeyboardOnSettle = true;
                mActivity.ensureTerminalSession();
            }
        }
        mBehavior.setState(state);
    }

    private void onSheetStateChanged(int state) {
        // Pada setengah layar, bagian bawah panel berada di luar layar.
        // Padding bawah menjaga isi terminal tetap muncul seluruhnya.
        int bottom = (state == BottomSheetBehavior.STATE_HALF_EXPANDED)
            ? Math.max(0, mSheet.getTop()) : 0;
        if (mSheet.getPaddingBottom() != bottom) {
            mSheet.setPadding(0, 0, 0, bottom);
        }

        View terminalView = mActivity.findViewById(R.id.terminal_view);
        if (state == BottomSheetBehavior.STATE_HIDDEN) {
            mTerminalOpen = false;
            mShowKeyboardOnSettle = false;
            mSheet.setVisibility(View.INVISIBLE);
            if (terminalView != null) {
                InputMethodManager imm = (InputMethodManager)
                    mActivity.getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.hideSoftInputFromWindow(terminalView.getWindowToken(), 0);
            }
            setTerminalFocusable(false);
        } else if (state == BottomSheetBehavior.STATE_HALF_EXPANDED
            || state == BottomSheetBehavior.STATE_EXPANDED) {
            if (terminalView != null) {
                terminalView.requestFocus();
                if (mShowKeyboardOnSettle) {
                    mShowKeyboardOnSettle = false;
                    terminalView.post(() -> {
                        InputMethodManager imm = (InputMethodManager)
                            mActivity.getSystemService(Context.INPUT_METHOD_SERVICE);
                        if (imm != null) imm.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT);
                    });
                }
            }
        }
    }

    private void setTerminalFocusable(boolean focusable) {
        View terminalView = mActivity.findViewById(R.id.terminal_view);
        if (terminalView == null) return;
        if (focusable) {
            terminalView.setFocusable(true);
            terminalView.setFocusableInTouchMode(true);
        } else {
            terminalView.clearFocus();
            terminalView.setFocusable(false);
            terminalView.setFocusableInTouchMode(false);
        }
    }

    private void bindClick(int id, View.OnClickListener listener) {
        View view = mActivity.findViewById(id);
        if (view != null) view.setOnClickListener(listener);
    }
}
