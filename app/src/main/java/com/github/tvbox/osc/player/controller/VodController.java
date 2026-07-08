package com.github.tvbox.osc.player.controller;

import android.app.Activity;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.os.Build;
import android.os.Handler;
import android.os.Message;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.webkit.WebView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.base.BaseActivity;
import com.github.tvbox.osc.bean.ParseBean;
import com.github.tvbox.osc.player.MyVideoView;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.server.ControlManager;
import com.github.tvbox.osc.server.RemoteServer;
import com.github.tvbox.osc.subtitle.widget.SimpleSubtitleView;
import com.github.tvbox.osc.ui.adapter.ParseAdapter;
import com.github.tvbox.osc.ui.adapter.SelectDialogAdapter;
import com.github.tvbox.osc.ui.dialog.SelectDialog;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.M3u8;
import com.github.tvbox.osc.util.PlaybackUrlNormalizer;
import com.github.tvbox.osc.util.PlayerHelper;
import com.github.tvbox.osc.util.ScreenUtils;
import com.github.tvbox.osc.util.SubtitleHelper;
import com.github.tvbox.osc.util.VideoParseRuler;
import com.github.tvbox.osc.util.thunder.Jianpian;
import com.github.tvbox.osc.util.thunder.Thunder;
import com.lzy.okgo.OkGo;
import com.lzy.okgo.callback.AbsCallback;
import com.lzy.okgo.model.HttpHeaders;
import com.lzy.okgo.model.Response;
import com.orhanobut.hawk.Hawk;
import com.owen.tvrecyclerview.widget.TvRecyclerView;
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager;

import org.jetbrains.annotations.NotNull;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.xwalk.core.XWalkView;

import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import java.util.Date;
import java.util.Map;
import java.util.Arrays;

import xyz.doikki.videoplayer.player.VideoView;
import xyz.doikki.videoplayer.util.PlayerUtils;

import static xyz.doikki.videoplayer.util.PlayerUtils.stringForTime;
import static xyz.doikki.videoplayer.util.PlayerUtils.seconds2Time;
import static xyz.doikki.videoplayer.util.PlayerUtils.safeTimeMs;

public class VodController extends BaseController {
    private static final long COMPLETION_GUARD_MS = 10_000L;
    private static final String TAG = "VodController";
    private boolean consumeBackKeyUpAfterFullScreenExit = false;
    private boolean embeddedPreviewMode = true;
    private boolean forceFullScreenInputMode = false;

    public VodController(@NonNull @NotNull Context context) {
        super(context);
        mHandlerCallback = new HandlerCallback() {
            @Override
            public void callback(Message msg) {
                switch (msg.what) {
                    case 1000: { // seek 刷新
                        mProgressRoot.setVisibility(VISIBLE);
                        break;
                    }
                    case 1001: { // seek 关闭
                        mProgressRoot.setVisibility(GONE);
                        break;
                    }
                    case 1002: { // 显示底部菜单
                        if (!isPlayerFullScreen()) {
                            mBottomRoot.setVisibility(GONE);
                            updateBottomMenuFocusMode(false);
                            applyEmbeddedPreviewMode();
                            break;
                        }
                        mBottomRoot.setVisibility(VISIBLE);
                        mTopContainer.setVisibility(isPlayerFullScreen() ? GONE : VISIBLE);
                        mTopRoot1.setVisibility(isPlayerFullScreen() ? GONE : VISIBLE);
                        mTopRoot2.setVisibility(isPlayerFullScreen() ? GONE : VISIBLE);
                        mPlayLoadNetSpeedRightTop.setVisibility(isPlayerFullScreen() ? GONE : VISIBLE);
                        if(Hawk.get(HawkConfig.SCREEN_DISPLAY,GONE)==GONE){
                            mPlayPauseTime.setVisibility(VISIBLE);
                        }else {
                            net_play_speed.setVisibility(GONE);
                        }
                        mPlayTitle.setVisibility(GONE);
                        backBtn.setVisibility(ScreenUtils.isTv(context) ? INVISIBLE : VISIBLE);
                        showLockView();
                        updateBottomMenuFocusMode(true);
                        break;
                    }
                    case 1003: { // 隐藏底部菜单
                        mBottomRoot.setVisibility(GONE);
                        mTopContainer.setVisibility(GONE);
                        mTopRoot1.setVisibility(GONE);
                        mTopRoot2.setVisibility(GONE);
                        mPlayLoadNetSpeedRightTop.setVisibility(GONE);
                        if(Hawk.get(HawkConfig.SCREEN_DISPLAY,GONE)==GONE){
                            mPlayPauseTime.setVisibility(GONE);
                        }else {
                            net_play_speed.setVisibility(VISIBLE);
                        }
                        backBtn.setVisibility(INVISIBLE);
                        updateBottomMenuFocusMode(false);
                        applyEmbeddedPreviewMode();
                        break;
                    }
                    case 1004: { // 设置速度
                        if (isInPlaybackState()) {
                            try {
                                float speed = (float) mPlayerConfig.getDouble("sp");
                                mControlWrapper.setSpeed(speed);
                            } catch (JSONException e) {
                                e.printStackTrace();
                            }
                        } else
                            mHandler.sendEmptyMessageDelayed(1004, 100);
                        break;
                    }
                }
            }
        };
    }

    SeekBar mSeekBar;
    TextView mCurrentTime;
    TextView mTotalTime;
    boolean mIsDragging;
    LinearLayout mProgressRoot;
    TextView mProgressText;
    ImageView mProgressIcon;
    ImageView mLockView;
    LinearLayout mBottomRoot;
    LinearLayout mPlayBtnGroup;
    LinearLayout mTopContainer;
    LinearLayout mTopRoot1;
    LinearLayout mTopRoot2;
    LinearLayout mParseRoot;
    TvRecyclerView mGridParseView;
    TextView mPlayTitle;
    TextView mPlayTitle1;
    TextView mPlayLoadNetSpeedRightTop;
    TextView mNextBtn;
    TextView mPreBtn;
    TextView mPlayerScaleBtn;
    public TextView mPlayerSpeedBtn;
    TextView mPlayerBtn;
    TextView mPlayerRetry;
    TextView mPlayrefresh;
    public TextView mPlayerTimeStartEndText;
    public TextView mPlayerTimeStartBtn;
    public TextView mPlayerTimeSkipBtn;
    public TextView mPlayerTimeResetBtn;
    TextView mPlayPauseTime;
    TextView mPlayLoadNetSpeed;
    TextView mVideoSize;
    public SimpleSubtitleView mSubtitleView;
    TextView mResumeAnchor;
    TextView mZimuBtn;
    TextView mAudioTrackBtn;
    public TextView mLandscapePortraitBtn;
    private View backBtn;//返回键
    private boolean isClickBackBtn;
    TextView seekTime; //右上角进度时间显示
    TextView mScreenDisplay; //增加屏显开关
    LinearLayout tv_screen_display; //增加屏显布局
    TextView net_play_speed;
    private View mPlaybackFocusAnchor;
    private String lastLoggedFocusName;

    LockRunnable lockRunnable = new LockRunnable();
    private boolean isLock = false;
    Handler myHandle;
    Runnable myRunnable;
    int myHandleSeconds = 10000;//闲置多少毫秒秒关闭底栏  默认6秒

    int videoPlayState = 0;
    private boolean pendingResumeConfirm = false;
    private boolean menuNavigationStarted = false;
    private int bottomMenuFocusIndex = -1;

    private final Runnable myRunnable2 = new Runnable() {
        @SuppressLint("SetTextI18n")
        @Override
        public void run() {
            Date date = new Date();
            @SuppressLint("SimpleDateFormat") SimpleDateFormat timeFormat = new SimpleDateFormat("hh:mm a");
            mPlayPauseTime.setText(timeFormat.format(date));
            long mSpeed = mControlWrapper.getTcpSpeed();
            String speed = PlayerHelper.getDisplaySpeed(mSpeed,false);
            String speedBps = PlayerHelper.getDisplaySpeedBps(mSpeed,true);
            mPlayLoadNetSpeedRightTop.setText(speedBps);
            mPlayLoadNetSpeed.setText(speed);
            net_play_speed.setText(speedBps);
            int[] mVideoSizes = mControlWrapper.getVideoSize();
            String width = Integer.toString(mVideoSizes[0]);
            String height = Integer.toString(mVideoSizes[1]);
            mVideoSize.setText("[ " + width + " X " + height +" ]");

            mHandler.postDelayed(this, 1000);
        }
    };
    
    private void showLockView() {
        mLockView.setVisibility(ScreenUtils.isTv(getContext()) ? INVISIBLE : VISIBLE);
        mHandler.removeCallbacks(lockRunnable);
        mHandler.postDelayed(lockRunnable, 3000);
    }

    @Override
    protected void initView() {
        super.initView();
        mCurrentTime = findViewById(R.id.curr_time);
        mTotalTime = findViewById(R.id.total_time);
        mPlayTitle = findViewById(R.id.tv_info_name);
        mPlayTitle1 = findViewById(R.id.tv_info_name1);
        mPlayLoadNetSpeedRightTop = findViewById(R.id.tv_play_load_net_speed_right_top);
        mSeekBar = findViewById(R.id.seekBar);
        mProgressRoot = findViewById(R.id.tv_progress_container);
        mProgressIcon = findViewById(R.id.tv_progress_icon);
        mProgressText = findViewById(R.id.tv_progress_text);
        mBottomRoot = findViewById(R.id.bottom_container);
        mTopContainer = findViewById(R.id.top_container);
        mTopRoot1 = findViewById(R.id.tv_top_l_container);
        mTopRoot2 = findViewById(R.id.tv_top_r_container);
        mPlayBtnGroup = findViewById(R.id.play_btn_group);
        tv_screen_display = findViewById(R.id.tv_screen_display);
        net_play_speed = findViewById(R.id.net_play_speed);
        mParseRoot = findViewById(R.id.parse_root);
        mGridParseView = findViewById(R.id.mGridParseView);
        mPlayerRetry = findViewById(R.id.play_retry);
        mPlayrefresh = findViewById(R.id.play_refresh);
        mNextBtn = findViewById(R.id.play_next);
        mPreBtn = findViewById(R.id.play_pre);
        mPlayerScaleBtn = findViewById(R.id.play_scale);
        mPlayerSpeedBtn = findViewById(R.id.play_speed);
        mPlayerBtn = findViewById(R.id.play_player);
        mPlayerTimeStartEndText = findViewById(R.id.play_time_start_end_text);
        mPlayerTimeStartBtn = findViewById(R.id.play_time_start);
        mPlayerTimeSkipBtn = findViewById(R.id.play_time_end);
        mPlayerTimeResetBtn = findViewById(R.id.play_time_reset);
        mPlayPauseTime = findViewById(R.id.tv_sys_time);
        mPlayLoadNetSpeed = findViewById(R.id.tv_play_load_net_speed);
        mVideoSize = findViewById(R.id.tv_videosize);
        mSubtitleView = findViewById(R.id.subtitle_view);
        mResumeAnchor = findViewById(R.id.play_resume_anchor);
        mZimuBtn = findViewById(R.id.zimu_select);
        mAudioTrackBtn = findViewById(R.id.audio_track_select);
        mLandscapePortraitBtn = findViewById(R.id.landscape_portrait);
        backBtn = findViewById(R.id.tv_back);
        seekTime = findViewById(R.id.tv_seek_time);
        mScreenDisplay = findViewById(R.id.screen_display);
        backBtn.setFocusable(false);
        backBtn.setFocusableInTouchMode(false);
        backBtn.setClickable(false);
        mLockView = findViewById(R.id.tv_lock);
        mLockView.setFocusable(false);
        mLockView.setFocusableInTouchMode(false);
        mLockView.setClickable(false);
        mPlaybackFocusAnchor = findViewById(R.id.rootView);
        if (mPlaybackFocusAnchor != null) {
            mPlaybackFocusAnchor.setFocusable(true);
            mPlaybackFocusAnchor.setFocusableInTouchMode(true);
            if (mPlaybackFocusAnchor instanceof ViewGroup) {
                ((ViewGroup) mPlaybackFocusAnchor).setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
            }
            mPlaybackFocusAnchor.post(new Runnable() {
                @Override
                public void run() {
                    restorePlaybackFocus();
                }
            });
        }
        backBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (getContext() instanceof Activity) {
                    isClickBackBtn = true;
                    ((Activity) getContext()).onBackPressed();
                }
            }
        });
        mLockView.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                isLock = !isLock;
                mLockView.setImageResource(isLock ? R.drawable.icon_lock : R.drawable.icon_unlock);
                if (isLock) {
                    Message obtain = Message.obtain();
                    obtain.what = 1003;//隐藏底部菜单
                    mHandler.sendMessage(obtain);
                }
                showLockView();
            }
        });
        initSubtitleInfo();

        myHandle = new Handler();
        myRunnable = new Runnable() {
            @Override
            public void run() {
                hideBottom();
            }
        };
        post(new Runnable() {
            @Override
            public void run() {
                updateBottomMenuFocusMode(false);
                applyEmbeddedPreviewMode();
                restorePlaybackFocus();
            }
        });

        mPlayPauseTime.post(new Runnable() {
            @Override
            public void run() {
                mHandler.post(myRunnable2);
            }
        });

        mGridParseView.setLayoutManager(new V7LinearLayoutManager(getContext(), 0, false));
        ParseAdapter parseAdapter = new ParseAdapter();
        parseAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                ParseBean parseBean = parseAdapter.getItem(position);
                // 当前默认解析需要刷新
                int currentDefault = parseAdapter.getData().indexOf(ApiConfig.get().getDefaultParse());
                parseAdapter.notifyItemChanged(currentDefault);
                ApiConfig.get().setDefaultParse(parseBean);
                parseAdapter.notifyItemChanged(position);
                listener.changeParse(parseBean);
                hideBottom();
            }
        });
        mGridParseView.setAdapter(parseAdapter);
        parseAdapter.setNewData(ApiConfig.get().getParseBeanList());

        mSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) {
                    return;
                }

                long duration = mControlWrapper.getDuration();
                long newPosition = (duration * progress) / seekBar.getMax();
                if (mCurrentTime != null)
                    mCurrentTime.setText(stringForTime(safeTimeMs(newPosition)));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                mIsDragging = true;
                mControlWrapper.stopProgress();
                mControlWrapper.stopFadeOut();
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                myHandle.removeCallbacks(myRunnable);
                myHandle.postDelayed(myRunnable, myHandleSeconds);
                long duration = mControlWrapper.getDuration();
                long newPosition = (duration * seekBar.getProgress()) / seekBar.getMax();
                mControlWrapper.seekTo(newPosition);
                resumePlaybackAfterSeek("touch");
                mIsDragging = false;
                mControlWrapper.startProgress();
                mControlWrapper.startFadeOut();
            }
        });
        mPlayerRetry.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                listener.replay(true);
                hideBottom();
            }
        });
        mPlayrefresh.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                listener.replay(false);
                hideBottom();
            }
        });
        mNextBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                listener.playNext(false);
                hideBottom();
            }
        });
        mPreBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                listener.playPre();
                hideBottom();
            }
        });
        mPlayerScaleBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                myHandle.removeCallbacks(myRunnable);
                myHandle.postDelayed(myRunnable, myHandleSeconds);
                try {
                    int scaleType = PlayerHelper.nextJava64TouchPhoneScale(mPlayerConfig.optInt("sc", VideoView.SCREEN_SCALE_DEFAULT));
                    mPlayerConfig.put("sc", scaleType);
                    updatePlayerCfgView();
                    listener.updatePlayerCfg();
                    mControlWrapper.setScreenScaleType(scaleType);
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
        });
        mPlayerSpeedBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                myHandle.removeCallbacks(myRunnable);
                myHandle.postDelayed(myRunnable, myHandleSeconds);
                try {
                    float speed = (float) mPlayerConfig.getDouble("sp");
                    speed += 0.25f;
                    if (speed > 3)
                        speed = 0.5f;
                    mPlayerConfig.put("sp", speed);
                    updatePlayerCfgView();
                    listener.updatePlayerCfg();
                    speed_old = speed;
                    mControlWrapper.setSpeed(speed);
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
        });

        mPlayerSpeedBtn.setOnLongClickListener(new OnLongClickListener() {
            @Override
            public boolean onLongClick(View view) {
                try {
                    mPlayerConfig.put("sp", 1.0f);
                    updatePlayerCfgView();
                    listener.updatePlayerCfg();
                    speed_old = 1.0f;
                    mControlWrapper.setSpeed(1.0f);
                } catch (JSONException e) {
                    e.printStackTrace();
                }
                return true;
            }
        });
        mPlayerBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                myHandle.removeCallbacks(myRunnable);
                myHandle.postDelayed(myRunnable, myHandleSeconds);
                try {
                    if (mPlayerConfig.getInt("pl") != PlayerHelper.PLAYER_TYPE_SYSTEM) {
                        mPlayerConfig.put("pl", PlayerHelper.PLAYER_TYPE_SYSTEM);
                        updatePlayerCfgView();
                        listener.updatePlayerCfg();
                        listener.replay(false);
                    } else {
                        Toast.makeText(getContext(), "当前固定使用系统硬解播放器", Toast.LENGTH_SHORT).show();
                    }
                    listener.setAllowSwitchPlayer(false);
                    hideBottom();
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
        });

        mPlayerBtn.setOnLongClickListener(new OnLongClickListener() {
            @Override
            public boolean onLongClick(View view) {
                myHandle.removeCallbacks(myRunnable);
                myHandle.postDelayed(myRunnable, myHandleSeconds);
                FastClickCheckUtil.check(view);
                Toast.makeText(getContext(), "系统硬解播放器已固定启用", Toast.LENGTH_SHORT).show();
                return true;
            }
        });
//        增加播放页面片头片尾时间重置
        mPlayerTimeResetBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                myHandle.removeCallbacks(myRunnable);
                myHandle.postDelayed(myRunnable, myHandleSeconds);
                try {
                    mPlayerConfig.put("et", 0);
                    mPlayerConfig.put("st", 0);
                    updatePlayerCfgView();
                    listener.updatePlayerCfg();
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
        });
        mPlayerTimeStartBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                myHandle.removeCallbacks(myRunnable);
                myHandle.postDelayed(myRunnable, myHandleSeconds);
                try {
                    int current = safeTimeMs(mControlWrapper.getCurrentPosition());
                    int duration = safeTimeMs(mControlWrapper.getDuration());
                    if (current > duration / 2) return;
                    mPlayerConfig.put("st",current/1000);
                    updatePlayerCfgView();
                    listener.updatePlayerCfg();
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
        });
        mPlayerTimeStartBtn.setOnLongClickListener(new OnLongClickListener() {
            @Override
            public boolean onLongClick(View view) {
                try {
                    mPlayerConfig.put("st", 0);
                    updatePlayerCfgView();
                    listener.updatePlayerCfg();
                } catch (JSONException e) {
                    e.printStackTrace();
                }
                return true;
            }
        });
        mPlayerTimeSkipBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                myHandle.removeCallbacks(myRunnable);
                myHandle.postDelayed(myRunnable, myHandleSeconds);
                try {
                    int current = safeTimeMs(mControlWrapper.getCurrentPosition());
                    int duration = safeTimeMs(mControlWrapper.getDuration());
                    if (current < duration / 2) return;
                    mPlayerConfig.put("et", (duration - current)/1000);
                    updatePlayerCfgView();
                    listener.updatePlayerCfg();
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
        });
        mPlayerTimeSkipBtn.setOnLongClickListener(new OnLongClickListener() {
            @Override
            public boolean onLongClick(View view) {
                try {
                    mPlayerConfig.put("et", 0);
                    updatePlayerCfgView();
                    listener.updatePlayerCfg();
                } catch (JSONException e) {
                    e.printStackTrace();
                }
                return true;
            }
        });
        mZimuBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                FastClickCheckUtil.check(view);
                listener.selectSubtitle();
                hideBottom();
            }
        });
        mZimuBtn.setOnLongClickListener(new OnLongClickListener() {
            @Override
            public boolean onLongClick(View view) {
                mSubtitleView.setVisibility(View.GONE);
                mSubtitleView.destroy();
                mSubtitleView.clearSubtitleCache();
                mSubtitleView.isInternal = false;
                hideBottom();
                Toast.makeText(getContext(), "字幕已关闭", Toast.LENGTH_SHORT).show();
                return true;
            }
        });
        mAudioTrackBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                FastClickCheckUtil.check(view);
                listener.selectAudioTrack();
                hideBottom();
            }
        });
        mLandscapePortraitBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                FastClickCheckUtil.check(view);
                setLandscapePortrait();
                hideBottom();
            }
        });
        //屏显
        int disPlay = Hawk.get(HawkConfig.SCREEN_DISPLAY, GONE);
        seekTime.setVisibility(disPlay);
        net_play_speed.setVisibility(disPlay);
        mPlayPauseTime.setVisibility(disPlay);
        mScreenDisplay.setTextColor(disPlay==VISIBLE?getResources().getColor(R.color.color_02F8E1): Color.WHITE);
        mScreenDisplay.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                int disPlay =(Hawk.get(HawkConfig.SCREEN_DISPLAY, GONE) == VISIBLE) ? GONE : VISIBLE;
                seekTime.setVisibility(disPlay);
                net_play_speed.setVisibility(disPlay);
                if(disPlay==VISIBLE)mPlayPauseTime.setVisibility(disPlay);
                Hawk.put(HawkConfig.SCREEN_DISPLAY, disPlay);
                mScreenDisplay.setTextColor(disPlay==VISIBLE?getResources().getColor(R.color.color_02F8E1): Color.WHITE);
                hideBottom();
            }
        });
        mNextBtn.setNextFocusLeftId(R.id.screen_display);
        mScreenDisplay.setNextFocusRightId(R.id.play_next);
    }

    private void hideLiveAboutBtn() {
        if (mControlWrapper != null && mControlWrapper.getDuration() == 0) {
            mPlayerSpeedBtn.setVisibility(GONE);
            mPlayerTimeStartEndText.setVisibility(GONE);
            mPlayerTimeStartBtn.setVisibility(GONE);
            mPlayerTimeSkipBtn.setVisibility(GONE);
            mPlayerTimeResetBtn.setVisibility(GONE);
        } else {
            mPlayerSpeedBtn.setVisibility(View.VISIBLE);
            mPlayerTimeStartEndText.setVisibility(View.VISIBLE);
            mPlayerTimeStartBtn.setVisibility(View.VISIBLE);
            mPlayerTimeSkipBtn.setVisibility(View.VISIBLE);
            mPlayerTimeResetBtn.setVisibility(View.VISIBLE);
        }
    }

    public void initLandscapePortraitBtnInfo() {
        if(mControlWrapper!=null && mActivity!=null){
            int width = mControlWrapper.getVideoSize()[0];
            int height = mControlWrapper.getVideoSize()[1];
            double screenSqrt = ScreenUtils.getSqrt(mActivity);
            if (screenSqrt < 10.0 && width <= height) {
                mLandscapePortraitBtn.setVisibility(View.VISIBLE);
                mLandscapePortraitBtn.setText("竖屏");
            }
        }
    }

    void setLandscapePortrait() {
        int requestedOrientation = mActivity.getRequestedOrientation();
        if (requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE || requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE || requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE) {
            mLandscapePortraitBtn.setText("横屏");
            mActivity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
        } else if (requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT || requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT || requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT) {
            mLandscapePortraitBtn.setText("竖屏");
            mActivity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        }
    }

    void initSubtitleInfo() {
        int subtitleTextSize = SubtitleHelper.getTextSize(mActivity);
        mSubtitleView.setTextSize(subtitleTextSize);
    }

    @Override
    protected int getLayoutId() {
        return R.layout.player_vod_control_view;
    }

    public void showParse(boolean userJxList) {
        mParseRoot.setVisibility(userJxList ? VISIBLE : GONE);
    }

    private JSONObject mPlayerConfig = null;

    public void setPlayerConfig(JSONObject playerCfg) {
        this.mPlayerConfig = playerCfg;
        updatePlayerCfgView();
    }

    void updatePlayerCfgView() {
        try {
            int playerType = mPlayerConfig.getInt("pl");
            mPlayerBtn.setText(PlayerHelper.getPlayerName(playerType));
            int scaleType = PlayerHelper.sanitizeScaleForPlayer(playerType, mPlayerConfig.getInt("sc"));
            if (scaleType != mPlayerConfig.getInt("sc")) {
                mPlayerConfig.put("sc", scaleType);
            }
            mPlayerScaleBtn.setText(PlayerHelper.getScaleName(scaleType));
            mPlayerSpeedBtn.setText("x" + mPlayerConfig.getDouble("sp"));
            mPlayerTimeStartBtn.setText(stringForTime(mPlayerConfig.getInt("st") * 1000));
            mPlayerTimeSkipBtn.setText(stringForTime(mPlayerConfig.getInt("et") * 1000));
            mAudioTrackBtn.setVisibility(VISIBLE);
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    public void setTitle(String playTitleInfo) {
        mPlayTitle.setText(playTitleInfo);
        mPlayTitle1.setText(playTitleInfo);
    }

    public void setUrlTitle(String playTitleInfo) {
        mPlayTitle.setText(playTitleInfo);
    }

    public void resetSpeed() {
        skipEnd = true;
        skipEndGuardUntilMs = System.currentTimeMillis() + 4500L;
        mHandler.removeMessages(1004);
        mHandler.sendEmptyMessageDelayed(1004, 100);
    }

    public interface VodControlListener {
        void playNext(boolean rmProgress);

        void playPre();

        void prepared();

        void changeParse(ParseBean pb);

        void updatePlayerCfg();

        void replay(boolean replay);

        void errReplay();

        void selectSubtitle();

        void selectAudioTrack();

        void startPlayUrl(String url, HashMap<String, String> headers);

        void setAllowSwitchPlayer(boolean isAllow);
    }

    public void setListener(VodControlListener listener) {
        this.listener = listener;
    }

    private VodControlListener listener;

    private boolean skipEnd = true;
    private long skipEndGuardUntilMs = 0L;

    @SuppressLint("SetTextI18n")
    @Override
    protected void setProgress(int duration, int position) {

        if (mIsDragging) {
            return;
        }
        super.setProgress(duration, position);
        boolean skipEndGuardActive = System.currentTimeMillis() < skipEndGuardUntilMs;
        if (skipEnd && !skipEndGuardActive && position != 0 && duration != 0) {
            int et = 0;
            try {
                et = mPlayerConfig.getInt("et");
            } catch (JSONException e) {
                e.printStackTrace();
            }
            long remainingMs = duration - position;
            long allowedTailMs = et > 0 ? Math.min(et * 1000L, COMPLETION_GUARD_MS) : 0L;
            if (allowedTailMs > 0L && remainingMs <= allowedTailMs) {
                skipEnd = false;
                listener.playNext(true);
            }
        }
        mCurrentTime.setText(stringForTime(position));
        mTotalTime.setText(stringForTime(duration));
        seekTime.setText((seconds2Time(position)) + " | " + (seconds2Time(duration))); //右上角进度条时间显示
        if (duration > 0) {
            mSeekBar.setEnabled(true);
            int pos = (int) (position * 1.0 / duration * mSeekBar.getMax());
            mSeekBar.setProgress(pos);
        } else {
            mSeekBar.setEnabled(false);
        }
        int percent = mControlWrapper.getBufferedPercentage();
        if (percent >= 95) {
            mSeekBar.setSecondaryProgress(mSeekBar.getMax());
        } else {
            mSeekBar.setSecondaryProgress(percent * 10);
        }
    }

    private boolean simSlideStart = false;
    private int simSeekPosition = 0;
    private long simSlideOffset = 0;
    private long lastSlideTime = 0;
    private long lastRemoteSeekCommitTime = 0;
    private boolean simSeekCommitted = false;
    private static final long FULLSCREEN_SEEK_COMMIT_GUARD_MS = 520L;
    private int pendingSeekRetryCount = 0;
    private String pendingSeekReason = null;
    private final Runnable pendingRemoteSeekCommitRunnable = new Runnable() {
        @Override
        public void run() {
            if (TextUtils.isEmpty(pendingSeekReason) || simSeekPosition < 0) {
                pendingSeekRetryCount = 0;
                pendingSeekReason = null;
                return;
            }
            if (shouldDelaySeekUntilFullscreenStable() && pendingSeekRetryCount < 4) {
                pendingSeekRetryCount++;
                mHandler.postDelayed(this, FULLSCREEN_SEEK_COMMIT_GUARD_MS / 2);
                return;
            }
            String reason = pendingSeekReason;
            pendingSeekReason = null;
            pendingSeekRetryCount = 0;
            commitRemoteSeekNow(reason + "-stable");
        }
    };
    private int seekResumeCheckCount = 0;
    private final Runnable seekResumeCheckRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isInPlaybackState()) {
                return;
            }
            restorePlaybackAfterSeek("delayed-" + seekResumeCheckCount);
            seekResumeCheckCount++;
            if (seekResumeCheckCount < 3 && mControlWrapper != null && !mControlWrapper.isPlaying()) {
                mHandler.postDelayed(this, 350);
            }
        }
    };

    public void tvSlideStop() {
        if (!simSlideStart)
            return;
        if (!simSeekCommitted) {
            commitRemoteSeek("remote-stop");
        }
        simSlideStart = false;
        simSeekPosition = 0;
        simSlideOffset = 0;
        simSeekCommitted = false;
    }

    private void commitRemoteSeek(String reason) {
        if (mControlWrapper == null || simSeekPosition < 0) {
            return;
        }
        if (shouldDelaySeekUntilFullscreenStable()) {
            pendingSeekReason = reason;
            mHandler.removeCallbacks(pendingRemoteSeekCommitRunnable);
            mHandler.postDelayed(pendingRemoteSeekCommitRunnable, FULLSCREEN_SEEK_COMMIT_GUARD_MS);
            Log.i(TAG, "commitRemoteSeek delayed reason=" + reason + " pos=" + simSeekPosition);
            return;
        }
        commitRemoteSeekNow(reason);
    }

    private void commitRemoteSeekNow(String reason) {
        if (mControlWrapper == null || simSeekPosition < 0) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastRemoteSeekCommitTime < 160 && !"remote-stop".equals(reason)) {
            return;
        }
        lastRemoteSeekCommitTime = now;
        Log.i(TAG, "commitRemoteSeek reason=" + reason + " pos=" + simSeekPosition);
        mControlWrapper.seekTo(simSeekPosition);
        simSeekCommitted = true;
        resumePlaybackAfterSeek(reason);
    }

    private boolean shouldDelaySeekUntilFullscreenStable() {
        return mControlWrapper != null
                && mControlWrapper.isFullScreen()
                && mControlWrapper.isFullScreenViewMoving();
    }

    private void resumePlaybackAfterSeek(String reason) {
        if (!shouldControllerActivelyResumeAfterSeek()) {
            try {
                if (mControlWrapper != null) {
                    mControlWrapper.startProgress();
                    mControlWrapper.startFadeOut();
                }
            } catch (Throwable ignored) {
            }
            return;
        }
        restorePlaybackAfterSeek(reason);
        mHandler.removeCallbacks(seekResumeCheckRunnable);
        seekResumeCheckCount = 0;
        mHandler.postDelayed(seekResumeCheckRunnable, 220);
    }

    private void restorePlaybackAfterSeek(String reason) {
        if (mControlWrapper == null || !isInPlaybackState()) {
            return;
        }
        try {
            boolean resumeNeeded = videoPlayState == VideoView.STATE_PAUSED
                    || videoPlayState == VideoView.STATE_BUFFERING
                    || videoPlayState == VideoView.STATE_BUFFERED
                    || !mControlWrapper.isPlaying();
            if (resumeNeeded) {
                Log.i(TAG, "resumePlaybackAfterSeek start reason=" + reason + " state=" + videoPlayState);
                mControlWrapper.start();
            }
            mControlWrapper.startProgress();
            mControlWrapper.startFadeOut();
        } catch (Throwable th) {
            LOG.e(TAG + " resumePlaybackAfterSeek failed: " + th.getMessage());
        }
    }
    public void tvSlideStart(int dir) {
        int duration = safeTimeMs(mControlWrapper.getDuration());
        if (duration <= 0)
            return;

        long currentTime = System.currentTimeMillis();
        final int baseSkip = 10000; // 基础跳转10秒
        final float accelerationFactor = 2.0f; // 连续操作时的加速因子
        final long threshold = 800; // 操作间隔阈值500ms

        if (!simSlideStart) {
            simSlideStart = true;
            simSlideOffset = (long) baseSkip * dir;
        } else {
            if (currentTime - lastSlideTime <= threshold) {
                simSlideOffset += (baseSkip * accelerationFactor * dir);
            } else {
                simSlideOffset = (long) baseSkip * dir;
            }
        }
        lastSlideTime = currentTime;
        int currentPosition = safeTimeMs(mControlWrapper.getCurrentPosition());
        int position = (int) (currentPosition + simSlideOffset);
        if (position > duration) position = duration;
        if (position < 0) position = 0;
        updateSeekUI(currentPosition, position, duration);
        simSeekPosition = position;
    }

    @Override
    protected void updateSeekUI(int curr, int seekTo, int duration) {
        super.updateSeekUI(curr, seekTo, duration);
        if (seekTo > curr) {
            mProgressIcon.setImageResource(R.drawable.icon_pre);
        } else {
            mProgressIcon.setImageResource(R.drawable.icon_back);
        }
        mProgressText.setText(stringForTime(seekTo) + " / " + stringForTime(duration));
        mHandler.sendEmptyMessage(1000);
        mHandler.removeMessages(1001);
        mHandler.sendEmptyMessageDelayed(1001, 1000);
    }

    @Override
    protected void onPlayStateChanged(int playState) {
        super.onPlayStateChanged(playState);
        videoPlayState = playState;
        switch (playState) {
            case VideoView.STATE_IDLE:
                break;
            case VideoView.STATE_PLAYING:
                initLandscapePortraitBtnInfo();
                resetSpeed();
                startProgress();
                if (!isPlayerFullScreen() && !isBottomVisible()) {
                    updateBottomMenuFocusMode(false);
                    restorePlaybackFocus();
                }
                break;
            case VideoView.STATE_PAUSED:
                mTopContainer.setVisibility(GONE);
                mTopRoot1.setVisibility(GONE);
                mTopRoot2.setVisibility(GONE);
                mPlayLoadNetSpeedRightTop.setVisibility(GONE);
                mPlayTitle.setVisibility(VISIBLE);
                break;
            case VideoView.STATE_ERROR:
                listener.errReplay();
                break;
            case VideoView.STATE_PREPARED:
                mPlayLoadNetSpeed.setVisibility(GONE);
                hideLiveAboutBtn();
                listener.prepared();
                if (!isPlayerFullScreen() && !isBottomVisible()) {
                    updateBottomMenuFocusMode(false);
                    restorePlaybackFocus();
                }
                break;
            case VideoView.STATE_BUFFERED:
                mPlayLoadNetSpeed.setVisibility(GONE);
                if (!isPlayerFullScreen() && !isBottomVisible()) {
                    updateBottomMenuFocusMode(false);
                    restorePlaybackFocus();
                }
                break;
            case VideoView.STATE_PREPARING:
            case VideoView.STATE_BUFFERING:
                if(mProgressRoot.getVisibility()==GONE)mPlayLoadNetSpeed.setVisibility(VISIBLE);
                break;
            case VideoView.STATE_PLAYBACK_COMPLETED:
                listener.playNext(true);
                break;
        }
    }

    boolean isBottomVisible() {
        return mBottomRoot.getVisibility() == VISIBLE;
    }

    private boolean isPlayerFullScreen() {
        return mControlWrapper != null && (mControlWrapper.isFullScreen() || forceFullScreenInputMode);
    }

    public void setForceFullScreenInputMode(boolean forceFullScreenInputMode) {
        this.forceFullScreenInputMode = forceFullScreenInputMode;
        if (forceFullScreenInputMode) {
            embeddedPreviewMode = false;
        }
    }

    public void setEmbeddedPreviewMode(boolean previewMode) {
        if (previewMode && forceFullScreenInputMode) {
            previewMode = false;
        }
        embeddedPreviewMode = previewMode;
        if (previewMode) {
            forceFullScreenInputMode = false;
        }
        applyEmbeddedPreviewMode();
    }

    public void syncFullScreenControlState() {
        boolean fullScreen = isPlayerFullScreen();
        embeddedPreviewMode = !fullScreen;
        applyEmbeddedPreviewMode();
        if (fullScreen) {
            updateBottomMenuFocusMode(false);
            restorePlaybackFocus();
        } else {
            forceEmbeddedPreviewMode();
        }
    }

    public void forceEmbeddedPreviewMode() {
        forceFullScreenInputMode = false;
        embeddedPreviewMode = true;
        pendingResumeConfirm = false;
        menuNavigationStarted = false;
        mHandler.removeMessages(1002);
        mHandler.removeMessages(1003);
        applyEmbeddedPreviewMode();
    }

    private void applyEmbeddedPreviewMode() {
        boolean preview = embeddedPreviewMode || !isPlayerFullScreen();
        if (!preview) {
            setControllerTreeInteractive(true);
            return;
        }
        pendingResumeConfirm = false;
        menuNavigationStarted = false;
        mHandler.removeMessages(1002);
        mHandler.removeMessages(1003);
        if (mBottomRoot != null) {
            mBottomRoot.setVisibility(GONE);
        }
        if (mTopContainer != null) {
            mTopContainer.setVisibility(GONE);
        }
        if (mTopRoot1 != null) {
            mTopRoot1.setVisibility(GONE);
        }
        if (mTopRoot2 != null) {
            mTopRoot2.setVisibility(GONE);
        }
        if (mProgressRoot != null) {
            mProgressRoot.setVisibility(GONE);
        }
        clearBottomMenuFocus();
        updateBottomMenuFocusMode(false);
        setControllerTreeInteractive(false);
    }

    private void setControllerTreeInteractive(boolean enabled) {
        boolean touchPhone = isJava64TouchPhone();
        boolean interactive = enabled || touchPhone;
        setFocusable(interactive);
        setFocusableInTouchMode(interactive);
        setClickable(interactive);
        setEnabled(true);
        if (!enabled) {
            clearFocus();
        }
        if (mPlaybackFocusAnchor != null) {
            mPlaybackFocusAnchor.setFocusable(interactive);
            mPlaybackFocusAnchor.setFocusableInTouchMode(interactive);
            // 64-bit 手机全屏触控必须直接落到控制器本身，不能被铺满全屏的 root anchor 吞掉。
            mPlaybackFocusAnchor.setClickable(!touchPhone && enabled);
            mPlaybackFocusAnchor.setLongClickable(false);
            mPlaybackFocusAnchor.setEnabled(true);
            if (mPlaybackFocusAnchor instanceof ViewGroup) {
                ((ViewGroup) mPlaybackFocusAnchor).setDescendantFocusability(interactive
                        ? ViewGroup.FOCUS_BEFORE_DESCENDANTS
                        : ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            }
            if (!enabled) {
                mPlaybackFocusAnchor.clearFocus();
            }
        }
    }

    private boolean isJava64TouchPhone() {
        if (!App.isJava64Build()) {
            return false;
        }
        Activity activity = PlayerUtils.scanForActivity(getContext());
        return activity instanceof BaseActivity && !((BaseActivity) activity).isTvDevice();
    }

    private boolean enterFullScreenFromPreview() {
        if (mControlWrapper == null || !isInPlaybackState()) {
            return false;
        }
        if (isPlayerFullScreen()) {
            return true;
        }
        LOG.i(TAG + " enterFullScreenFromPreview");
        forceFullScreenInputMode = true;
        embeddedPreviewMode = false;
        applyEmbeddedPreviewMode();
        mControlWrapper.startFullScreen();
        hideBottom();
        post(new Runnable() {
            @Override
            public void run() {
                syncFullScreenControlState();
                restorePlaybackFocus();
            }
        });
        return true;
    }

    void showBottom() {
        showBottom(false, false);
    }

    private void showBottom(boolean pauseResumeMode, boolean focusTimeRow) {
        if (!isPlayerFullScreen()) {
            applyEmbeddedPreviewMode();
            return;
        }
        setEmbeddedPreviewMode(false);
        pendingResumeConfirm = pauseResumeMode;
        menuNavigationStarted = false;
        mHandler.removeMessages(1003);
        mHandler.sendEmptyMessage(1002);
        if (mBottomRoot != null) {
            mBottomRoot.post(new Runnable() {
                @Override
                public void run() {
                    updateBottomMenuFocusMode(true);
                    if (pauseResumeMode) {
                        clearBottomMenuFocus();
                    } else if (focusTimeRow && isFocusableNow(mPlayerTimeStartBtn)) {
                        requestBottomMenuFocus(true);
                        menuNavigationStarted = true;
                    } else if (requestBottomMenuFocus(false)) {
                        menuNavigationStarted = true;
                    } else {
                        clearBottomMenuFocus();
                    }
                }
            });
        }
    }

    void showUpBottom() {
        showBottom(false, true);
    }

    void hideBottom() {
        pendingResumeConfirm = false;
        menuNavigationStarted = false;
        clearBottomMenuFocus();
        updateBottomMenuFocusMode(false);
        mHandler.removeMessages(1002);
        mHandler.sendEmptyMessage(1003);
        if (isPlayerFullScreen()) {
            restorePlaybackFocus();
        } else {
            applyEmbeddedPreviewMode();
        }
    }

    private void resumeFromBottomMenu() {
        pendingResumeConfirm = false;
        menuNavigationStarted = false;
        if (isInPlaybackState()) {
            if (shouldResumePlaybackNow()) {
                mControlWrapper.start();
            } else {
                mControlWrapper.pause();
            }
        }
        hideBottom();
    }

    private boolean shouldResumePlaybackNow() {
        return videoPlayState == VideoView.STATE_PAUSED
                || videoPlayState == VideoView.STATE_BUFFERING
                || videoPlayState == VideoView.STATE_BUFFERED
                || (mControlWrapper != null && !mControlWrapper.isPlaying());
    }

    private boolean isDirectionalMenuNavigationKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_UP
                || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                || keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
    }

    private void updateBottomMenuFocusMode(boolean visible) {
        boolean allowControls = visible && isPlayerFullScreen();
        int focusability = allowControls ? ViewGroup.FOCUS_AFTER_DESCENDANTS : ViewGroup.FOCUS_BLOCK_DESCENDANTS;
        setDescendantFocusabilitySafe(mTopContainer, ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        setDescendantFocusabilitySafe(mTopRoot1, ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        setDescendantFocusabilitySafe(mTopRoot2, ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        setDescendantFocusabilitySafe(mProgressRoot, ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        setContainerFocusability(mParseRoot, allowControls && mParseRoot != null && mParseRoot.getVisibility() == VISIBLE);
        if (mBottomRoot != null) {
            if (allowControls) {
                restoreMenuContainerTree(mBottomRoot);
            }
            mBottomRoot.setDescendantFocusability(focusability);
            mBottomRoot.setFocusable(false);
            mBottomRoot.setFocusableInTouchMode(false);
        }
        if (mPlayBtnGroup != null) {
            mPlayBtnGroup.setDescendantFocusability(focusability);
            mPlayBtnGroup.setFocusable(false);
            mPlayBtnGroup.setFocusableInTouchMode(false);
        }
        setPlaybackFocusAnchorForBottomMenu(allowControls);
        if (mGridParseView != null) {
            mGridParseView.setDescendantFocusability(focusability);
            boolean parseVisible = allowControls && mParseRoot != null && mParseRoot.getVisibility() == VISIBLE;
            mGridParseView.setFocusable(parseVisible);
            mGridParseView.setFocusableInTouchMode(false);
        }
        applyVisibleFocusState(mResumeAnchor, false);
        applyVisibleFocusState(mNextBtn, allowControls && isActuallyVisible(mNextBtn));
        applyVisibleFocusState(mPreBtn, allowControls && isActuallyVisible(mPreBtn));
        applyVisibleFocusState(mPlayerRetry, allowControls && isActuallyVisible(mPlayerRetry));
        applyVisibleFocusState(mPlayrefresh, allowControls && isActuallyVisible(mPlayrefresh));
        applyVisibleFocusState(mPlayerScaleBtn, allowControls && isActuallyVisible(mPlayerScaleBtn));
        applyVisibleFocusState(mPlayerSpeedBtn, allowControls && isActuallyVisible(mPlayerSpeedBtn));
        applyVisibleFocusState(mPlayerBtn, allowControls && isActuallyVisible(mPlayerBtn));
        applyVisibleFocusState(mPlayerTimeStartBtn, allowControls && isActuallyVisible(mPlayerTimeStartBtn));
        applyVisibleFocusState(mPlayerTimeSkipBtn, allowControls && isActuallyVisible(mPlayerTimeSkipBtn));
        applyVisibleFocusState(mPlayerTimeResetBtn, allowControls && isActuallyVisible(mPlayerTimeResetBtn));
        applyVisibleFocusState(mZimuBtn, allowControls && isActuallyVisible(mZimuBtn));
        applyVisibleFocusState(mAudioTrackBtn, allowControls && isActuallyVisible(mAudioTrackBtn));
        applyVisibleFocusState(mLandscapePortraitBtn, allowControls && isActuallyVisible(mLandscapePortraitBtn));
        applyVisibleFocusState(mScreenDisplay, allowControls && isActuallyVisible(mScreenDisplay));
        applyVisibleFocusState(backBtn, false);
        applyVisibleFocusState(mLockView, false);
        disableInvisibleFocusables(this, allowControls);
        pruneHiddenFocusables(this, allowControls);
        if (!allowControls) {
            clearBottomMenuFocus();
        }
    }

    private void restoreMenuContainerTree(View view) {
        if (!(view instanceof ViewGroup)) {
            return;
        }
        ViewGroup group = (ViewGroup) view;
        group.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        group.setFocusable(false);
        group.setFocusableInTouchMode(false);
        for (int i = 0; i < group.getChildCount(); i++) {
            restoreMenuContainerTree(group.getChildAt(i));
        }
    }

    private void setPlaybackFocusAnchorForBottomMenu(boolean bottomControlsVisible) {
        if (mPlaybackFocusAnchor == null) {
            return;
        }
        boolean touchPhone = isJava64TouchPhone();
        boolean anchorEnabled = !bottomControlsVisible;
        mPlaybackFocusAnchor.setFocusable(anchorEnabled);
        mPlaybackFocusAnchor.setFocusableInTouchMode(anchorEnabled);
        mPlaybackFocusAnchor.setClickable(!touchPhone && anchorEnabled);
        if (mPlaybackFocusAnchor instanceof ViewGroup) {
            ((ViewGroup) mPlaybackFocusAnchor).setDescendantFocusability(bottomControlsVisible
                    ? ViewGroup.FOCUS_AFTER_DESCENDANTS
                    : ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        }
        if (bottomControlsVisible && mPlaybackFocusAnchor.hasFocus()) {
            mPlaybackFocusAnchor.clearFocus();
        }
    }

    private boolean requestBottomMenuFocus(boolean preferTimeRow) {
        View current = findFocus();
        if (current != null && current != this && isAllowedFocusable(current, true) && isBottomMenuButtonVisible(current)) {
            forceFocusable(current, true);
            bottomMenuFocusIndex = indexOfVisibleBottomButton(current);
            updateBottomMenuActivatedState(current);
            return true;
        }
        View target = preferTimeRow && isBottomMenuButtonVisible(mPlayerTimeStartBtn) ? mPlayerTimeStartBtn : null;
        if (target == null) {
            target = firstFocusableBottomButton();
        }
        if (target == null) {
            return false;
        }
        forceFocusable(target, true);
        bottomMenuFocusIndex = indexOfVisibleBottomButton(target);
        updateBottomMenuActivatedState(target);
        if (!target.requestFocus()) {
            Log.i(TAG, "bottomMenuFocus virtual=" + focusName(target));
        } else {
            target.requestFocusFromTouch();
        }
        logFocus("bottomMenuFocus");
        return true;
    }

    private View firstFocusableBottomButton() {
        List<View> buttons = getVisibleBottomMenuButtons();
        return buttons.isEmpty() ? null : buttons.get(0);
    }

    private List<View> getVisibleBottomMenuButtons() {
        List<View> buttons = new ArrayList<>();
        for (View candidate : bottomMenuButtonCandidates()) {
            if (isBottomMenuButtonVisible(candidate)) {
                buttons.add(candidate);
            }
        }
        return buttons;
    }

    private List<View> bottomMenuButtonCandidates() {
        return Arrays.asList(
                mNextBtn,
                mPreBtn,
                mPlayerRetry,
                mPlayrefresh,
                mPlayerScaleBtn,
                mPlayerSpeedBtn,
                mPlayerBtn,
                mPlayerTimeStartBtn,
                mPlayerTimeSkipBtn,
                mPlayerTimeResetBtn,
                mZimuBtn,
                mAudioTrackBtn,
                mLandscapePortraitBtn,
                mScreenDisplay
        );
    }

    private int indexOfVisibleBottomButton(View view) {
        if (view == null) {
            return -1;
        }
        List<View> buttons = getVisibleBottomMenuButtons();
        for (int i = 0; i < buttons.size(); i++) {
            if (buttons.get(i) == view) {
                return i;
            }
        }
        return -1;
    }

    private boolean moveBottomMenuFocus(int keyCode) {
        List<View> buttons = getVisibleBottomMenuButtons();
        if (buttons.isEmpty()) {
            bottomMenuFocusIndex = -1;
            return false;
        }
        View current = findFocus();
        int currentIndex = indexOfVisibleBottomButton(current);
        if (currentIndex < 0) {
            currentIndex = bottomMenuFocusIndex;
        }
        if (currentIndex < 0 || currentIndex >= buttons.size()) {
            currentIndex = 0;
        } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            currentIndex = (currentIndex + 1) % buttons.size();
        } else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            currentIndex = (currentIndex - 1 + buttons.size()) % buttons.size();
        }
        View target = buttons.get(currentIndex);
        forceFocusable(target, true);
        bottomMenuFocusIndex = currentIndex;
        updateBottomMenuActivatedState(target);
        if (!target.requestFocus()) {
            Log.i(TAG, "bottomMenuMove virtual=" + focusName(target));
        } else {
            target.requestFocusFromTouch();
        }
        if (mPlayBtnGroup != null) {
            mPlayBtnGroup.requestChildFocus(target, target);
        }
        logFocus("bottomMenuMove");
        return true;
    }

    private boolean clickFocusedBottomMenuButton() {
        View focusedView = findFocus();
        if (focusedView == null || !isBottomMenuButtonVisible(focusedView) || !isAllowedFocusable(focusedView, true)) {
            List<View> buttons = getVisibleBottomMenuButtons();
            if (!buttons.isEmpty()) {
                int index = bottomMenuFocusIndex;
                if (index < 0 || index >= buttons.size()) {
                    index = 0;
                }
                focusedView = buttons.get(index);
                bottomMenuFocusIndex = index;
                updateBottomMenuActivatedState(focusedView);
            } else {
                if (!requestBottomMenuFocus(false)) {
                    return false;
                }
                focusedView = findFocus();
            }
        }
        if (focusedView != null && isBottomMenuButtonVisible(focusedView) && isAllowedFocusable(focusedView, true)) {
            Log.i(TAG, "bottomMenuClick " + focusName(focusedView));
            focusedView.performClick();
            return true;
        }
        return false;
    }

    private void setContainerFocusability(View view, boolean allowChildren) {
        if (view instanceof ViewGroup) {
            ((ViewGroup) view).setDescendantFocusability(allowChildren
                    ? ViewGroup.FOCUS_AFTER_DESCENDANTS
                    : ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        }
        if (view != null) {
            view.setFocusable(false);
            view.setFocusableInTouchMode(false);
        }
    }

    private void setDescendantFocusabilitySafe(View view, int focusability) {
        if (view instanceof ViewGroup) {
            ((ViewGroup) view).setDescendantFocusability(focusability);
        }
        applyVisibleFocusState(view, false);
    }

    private boolean isActuallyVisible(View view) {
        return view != null
                && view.getVisibility() == VISIBLE
                && view.isShown()
                && view.getAlpha() > 0.05f
                && (mBottomRoot == null || mBottomRoot.getVisibility() == VISIBLE)
                && (view == mBottomRoot || isDescendantOf(view, mBottomRoot));
    }

    private boolean isFocusableNow(View view) {
        return view != null && view.isFocusable() && isActuallyVisible(view);
    }

    private boolean isBottomMenuButtonVisible(View view) {
        return view != null
                && view.getVisibility() == VISIBLE
                && view.isShown()
                && view.getAlpha() > 0.05f
                && isAllowedFocusable(view, true)
                && (mBottomRoot == null || mBottomRoot.getVisibility() == VISIBLE)
                && (view == mBottomRoot || isDescendantOf(view, mBottomRoot));
    }

    private void forceFocusable(View view, boolean enabled) {
        if (view == null) {
            return;
        }
        view.setFocusable(enabled);
        view.setFocusableInTouchMode(enabled);
        if (enabled) {
            view.setClickable(true);
        } else {
            view.clearFocus();
        }
    }

    private void applyVisibleFocusState(View view, boolean enabled) {
        if (view == null) {
            return;
        }
        view.setFocusable(enabled);
        view.setFocusableInTouchMode(enabled);
        if (!enabled) {
            view.clearFocus();
        }
    }

    private void pruneHiddenFocusables(View view, boolean bottomVisible) {
        if (view == null) {
            return;
        }
        if (bottomVisible && isAllowedFocusable(view, true) && isActuallyVisible(view)) {
            forceFocusable(view, true);
            return;
        }
        if (view != mPlaybackFocusAnchor
                && view.isFocusable()
                && !isAllowedFocusable(view, bottomVisible)) {
            view.setFocusable(false);
            view.setFocusableInTouchMode(false);
            view.clearFocus();
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                pruneHiddenFocusables(group.getChildAt(i), bottomVisible);
            }
        }
    }

    private void disableInvisibleFocusables(View view, boolean bottomVisible) {
        if (view == null || view == mPlaybackFocusAnchor) {
            return;
        }
        if (bottomVisible && isAllowedFocusable(view, true) && isActuallyVisible(view)) {
            forceFocusable(view, true);
            return;
        }
        if (view.getVisibility() != VISIBLE || (!bottomVisible && view != this)) {
            clearFocusableTree(view);
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                disableInvisibleFocusables(group.getChildAt(i), bottomVisible);
            }
        }
    }

    private void clearFocusableTree(View view) {
        if (view == null || view == mPlaybackFocusAnchor) {
            return;
        }
        view.setFocusable(false);
        view.setFocusableInTouchMode(false);
        view.clearFocus();
        if (view instanceof ViewGroup) {
            ((ViewGroup) view).setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                clearFocusableTree(group.getChildAt(i));
            }
        }
    }

    private boolean isAllowedFocusable(View view, boolean bottomVisible) {
        if (view == mPlaybackFocusAnchor) {
            return true;
        }
        if (!bottomVisible) {
            return false;
        }
        return view == mNextBtn
                || view == mPreBtn
                || view == mPlayerRetry
                || view == mPlayrefresh
                || view == mPlayerScaleBtn
                || view == mPlayerSpeedBtn
                || view == mPlayerBtn
                || view == mPlayerTimeStartBtn
                || view == mPlayerTimeSkipBtn
                || view == mPlayerTimeResetBtn
                || view == mZimuBtn
                || view == mAudioTrackBtn
                || view == mLandscapePortraitBtn
                || view == mScreenDisplay
                || (mParseRoot != null
                && mParseRoot.getVisibility() == VISIBLE
                && mGridParseView != null
                && isDescendantOf(view, mGridParseView));
    }

    private boolean isDescendantOf(View child, View parent) {
        if (child == null || parent == null) {
            return false;
        }
        View current = child;
        while (current != null) {
            if (current == parent) {
                return true;
            }
            android.view.ViewParent viewParent = current.getParent();
            current = viewParent instanceof View ? (View) viewParent : null;
        }
        return false;
    }

    private void clearBottomMenuFocus() {
        bottomMenuFocusIndex = -1;
        updateBottomMenuActivatedState(null);
        View focusedView = findFocus();
        if (focusedView != null && focusedView != mPlaybackFocusAnchor) {
            focusedView.clearFocus();
        }
        if (mPlayBtnGroup != null) {
            mPlayBtnGroup.clearFocus();
        }
        if (mGridParseView != null) {
            mGridParseView.clearFocus();
        }
        if (mBottomRoot != null) {
            mBottomRoot.clearFocus();
        }
    }

    private void updateBottomMenuActivatedState(View activeView) {
        for (View candidate : bottomMenuButtonCandidates()) {
            if (candidate == null) {
                continue;
            }
            boolean active = candidate == activeView && isBottomMenuButtonVisible(candidate);
            candidate.setActivated(active);
            candidate.setSelected(active);
        }
    }

    private void restorePlaybackFocus() {
        if (!isPlayerFullScreen()) {
            clearBottomMenuFocus();
            if (mBottomRoot != null && mBottomRoot.getVisibility() != GONE) {
                mBottomRoot.setVisibility(GONE);
            }
            setControllerTreeInteractive(false);
            if (mPlaybackFocusAnchor != null && mPlaybackFocusAnchor.hasFocus()) {
                mPlaybackFocusAnchor.clearFocus();
            }
            if (hasFocus()) {
                clearFocus();
            }
            return;
        }
        setControllerTreeInteractive(true);
        if (isBottomVisible()) {
            setPlaybackFocusAnchorForBottomMenu(true);
            requestBottomMenuFocus(false);
            return;
        }
        if (mPlaybackFocusAnchor != null) {
            mPlaybackFocusAnchor.setFocusable(true);
            mPlaybackFocusAnchor.setFocusableInTouchMode(true);
            mPlaybackFocusAnchor.requestFocus();
            mPlaybackFocusAnchor.requestFocusFromTouch();
            logFocus("restorePlaybackFocus(anchor)");
        } else {
            setFocusable(true);
            setFocusableInTouchMode(true);
            requestFocus();
            requestFocusFromTouch();
            logFocus("restorePlaybackFocus(controller)");
        }
    }

    private void logFocus(String reason) {
        View focusedView = findFocus();
        String name = focusName(focusedView);
        if (!name.equals(lastLoggedFocusName)) {
            lastLoggedFocusName = name;
            Log.i(TAG, "focus " + reason + "=" + name);
        }
    }

    private String focusName(View focusedView) {
        String name = "null";
        if (focusedView != null) {
            int id = focusedView.getId();
            if (id != View.NO_ID) {
                try {
                    name = getResources().getResourceEntryName(id);
                } catch (Throwable ignored) {
                    name = focusedView.getClass().getSimpleName();
                }
            } else {
                name = focusedView.getClass().getSimpleName();
            }
        }
        return name;
    }

    @Override
    public boolean onKeyEvent(KeyEvent event) {
        myHandle.removeCallbacks(myRunnable);
        int keyCode = event.getKeyCode();
        int action = event.getAction();
        Log.i(TAG, "key action=" + action + " code=" + keyCode
                + " fullscreen=" + isPlayerFullScreen()
                + " bottom=" + isBottomVisible()
                + " focus=" + focusName(findFocus()));
        boolean isConfirmKey = keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
        boolean fullScreenWithHiddenMenu = isPlayerFullScreen() && !isBottomVisible();
        boolean isHorizontalSeekKey = keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_DPAD_LEFT;
        boolean isInPlayback = isInPlaybackState();
        if (!isPlayerFullScreen()) {
            mHandler.removeMessages(1002);
            if (mBottomRoot != null && mBottomRoot.getVisibility() != GONE) {
                mBottomRoot.setVisibility(GONE);
            }
            updateBottomMenuFocusMode(false);
            applyEmbeddedPreviewMode();
            return false;
        }
        setEmbeddedPreviewMode(false);
        if (action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_BACK) {
            Log.i(TAG, "onKeyEvent BACK fullscreen=" + isPlayerFullScreen() + " bottomVisible=" + isBottomVisible());
            if (isPlayerFullScreen() && isBottomVisible()) {
                hideBottom();
                consumeBackKeyUpAfterFullScreenExit = true;
                return true;
            }
            if (isPlayerFullScreen()) {
                hideBottom();
                consumeBackKeyUpAfterFullScreenExit = true;
                mControlWrapper.stopFullScreen();
                forceEmbeddedPreviewMode();
                return true;
            }
            if (isBottomVisible()) {
                hideBottom();
                consumeBackKeyUpAfterFullScreenExit = true;
                return true;
            }
        } else if (action == KeyEvent.ACTION_UP && keyCode == KeyEvent.KEYCODE_BACK && consumeBackKeyUpAfterFullScreenExit) {
            consumeBackKeyUpAfterFullScreenExit = false;
            return true;
        }
        if (fullScreenWithHiddenMenu && isHorizontalSeekKey) {
            restorePlaybackFocus();
            if (action == KeyEvent.ACTION_DOWN) {
                tvSlideStart(keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                return true;
            }
            if (action == KeyEvent.ACTION_UP) {
                tvSlideStop();
                return true;
            }
        }
        if (isBottomVisible()) {
            if (action == KeyEvent.ACTION_DOWN && isDirectionalMenuNavigationKey(keyCode)) {
                if (pendingResumeConfirm && !menuNavigationStarted) {
                    menuNavigationStarted = true;
                    pendingResumeConfirm = false;
                    moveBottomMenuFocus(keyCode);
                    mHandler.removeMessages(1002);
                    mHandler.removeMessages(1003);
                    myHandle.postDelayed(myRunnable, myHandleSeconds);
                    return true;
                }
                moveBottomMenuFocus(keyCode);
                mHandler.removeMessages(1002);
                mHandler.removeMessages(1003);
                myHandle.postDelayed(myRunnable, myHandleSeconds);
                return true;
            }
            if (action == KeyEvent.ACTION_UP && isDirectionalMenuNavigationKey(keyCode)) {
                return true;
            }
            if (action == KeyEvent.ACTION_DOWN && isConfirmKey) {
                LOG.i(TAG + " confirm bottomVisible fullscreen=" + isPlayerFullScreen() + " playback=" + isInPlaybackState()
                        + " pendingResume=" + pendingResumeConfirm + " menuNavigationStarted=" + menuNavigationStarted);
                if (pendingResumeConfirm && !menuNavigationStarted) {
                    resumeFromBottomMenu();
                    return true;
                }
                return clickFocusedBottomMenuButton();
            }
            if (action == KeyEvent.ACTION_UP && isConfirmKey) {
                return true;
            }
            mHandler.removeMessages(1002);
            mHandler.removeMessages(1003);
            myHandle.postDelayed(myRunnable, myHandleSeconds);
            return true;
        }
        if (action == KeyEvent.ACTION_DOWN) {
            if (isHorizontalSeekKey) {
                if (isInPlayback && isPlayerFullScreen()) {
                    tvSlideStart(keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                    return true;
                }
            } else if (isConfirmKey) {
                if (isInPlayback) {
                    LOG.i(TAG + " confirm bottomHidden fullscreen=" + isPlayerFullScreen() + " playback=true");
                    if (!isPlayerFullScreen()) {
                        return enterFullScreenFromPreview();
                    }
                    mControlWrapper.pause();
                    showBottom(true, false);
                    myHandle.postDelayed(myRunnable, myHandleSeconds);
                    return true;
                }
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode== KeyEvent.KEYCODE_MENU) {
                if (isInPlayback && !isPlayerFullScreen()) {
                    return enterFullScreenFromPreview();
                }
                if (!isBottomVisible()) {
                    showBottom();
                    myHandle.postDelayed(myRunnable, myHandleSeconds);
                    return true;
                }
            }
        } else if (action == KeyEvent.ACTION_UP) {
            if (isHorizontalSeekKey) {
                if (isInPlayback && isPlayerFullScreen()) {
                    tvSlideStop();
                    return true;
                }
            }
        }
        return super.onKeyEvent(event);
    }


    private boolean fromLongPress;
    private float speed_old = 1.0f;

    private void speedPlayStart(){
        fromLongPress = true;
        try {
            speed_old = (float) mPlayerConfig.getDouble("sp");
            float speed = 3.0f;
            mPlayerConfig.put("sp", speed);
            updatePlayerCfgView();
            listener.updatePlayerCfg();
            mControlWrapper.setSpeed(speed);
            findViewById(R.id.play_speed_3_container).setVisibility(View.VISIBLE);
        } catch (JSONException f) {
            f.printStackTrace();
        }
    }
    private void speedPlayEnd(){
        if (fromLongPress) {
            fromLongPress =false;
            try {
                float speed = speed_old;
                mPlayerConfig.put("sp", speed);
                updatePlayerCfgView();
                listener.updatePlayerCfg();
                mControlWrapper.setSpeed(speed);
            } catch (JSONException f) {
                f.printStackTrace();
            }
            findViewById(R.id.play_speed_3_container).setVisibility(View.GONE);
        }
    }
    @Override
    public void onLongPress(MotionEvent e) {
        if (videoPlayState!=VideoView.STATE_PAUSED) {
            speedPlayStart();
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (isLock) {
            if (e != null && e.getAction() == MotionEvent.ACTION_UP) {
                showLockView();
            }
            return true;
        }
        if (e.getAction() == MotionEvent.ACTION_UP) {
            speedPlayEnd();
        }
        return super.onTouchEvent(e);
    }

    @Override
    protected void onGestureSeekCompleted(int seekPosition) {
        resumePlaybackAfterSeek("gesture");
    }


    private final Handler mmHandler = new Handler();
    private Runnable mLongPressRunnable;
    private static final long LONG_PRESS_DELAY = 800;
    private boolean isLongPressTriggered = false;

    private boolean setMinPlayTimeChange(String typeEt,boolean increase){
        myHandle.removeCallbacks(myRunnable);
        myHandle.postDelayed(myRunnable, myHandleSeconds);
        try {
            int currentValue = mPlayerConfig.optInt(typeEt, 0);
            if(currentValue!=0){
                int newValue = increase ? currentValue + 1 : currentValue - 1;
                if(newValue < 0) {
                    newValue = 0;
                }
                mPlayerConfig.put(typeEt,newValue);
                updatePlayerCfgView();
                listener.updatePlayerCfg();
                return true;
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return false;
    }
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (isBottomVisible()) {
            boolean isConfirmKey = keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                    || keyCode == KeyEvent.KEYCODE_ENTER
                    || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
            if (isDirectionalMenuNavigationKey(keyCode)) {
                if (pendingResumeConfirm && !menuNavigationStarted) {
                    menuNavigationStarted = true;
                    pendingResumeConfirm = false;
                }
                moveBottomMenuFocus(keyCode);
                mHandler.removeMessages(1002);
                mHandler.removeMessages(1003);
                myHandle.postDelayed(myRunnable, myHandleSeconds);
                return true;
            }
            if (isConfirmKey) {
                if (pendingResumeConfirm && !menuNavigationStarted) {
                    resumeFromBottomMenu();
                    return true;
                }
                return clickFocusedBottomMenuButton();
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP ) {
                if(mPlayerTimeStartBtn.hasFocus()){
                    if(setMinPlayTimeChange("st",true)){
                        return true;
                    }
                }
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN ) {
                if(mPlayerTimeStartBtn.hasFocus()){
                    if(setMinPlayTimeChange("st",false))return true;
                }
                return true;
            }
            return keyCode == KeyEvent.KEYCODE_BACK;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP && event.getRepeatCount() == 0) {
            isLongPressTriggered = false;
            mLongPressRunnable = new Runnable() {
                @Override
                public void run() {
                    speedPlayStart();
                    isLongPressTriggered = true;
                }
            };
            mmHandler.postDelayed(mLongPressRunnable, LONG_PRESS_DELAY);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            // 移除长按回调
            if (mLongPressRunnable != null) {
                mmHandler.removeCallbacks(mLongPressRunnable);
                mLongPressRunnable = null;
            }
            if (isLongPressTriggered) {
                speedPlayEnd();
            } else {
                if (!isBottomVisible() && isInPlaybackState() && !isPlayerFullScreen()) {
                    return enterFullScreenFromPreview();
                }
                if (!isBottomVisible()) {
                    showUpBottom();
                    myHandle.postDelayed(myRunnable, myHandleSeconds);
                } else {
                    return false;
                }
            }
            return true;
        }
        if (isBottomVisible()) {
            if (isDirectionalMenuNavigationKey(keyCode)) {
                mHandler.removeMessages(1002);
                mHandler.removeMessages(1003);
                myHandle.postDelayed(myRunnable, myHandleSeconds);
                return true;
            }
            return keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                    || keyCode == KeyEvent.KEYCODE_ENTER
                    || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                    || keyCode == KeyEvent.KEYCODE_BACK;
        }
        return super.onKeyUp(keyCode, event);
    }

    private boolean shouldControllerActivelyResumeAfterSeek() {
        MyVideoView videoView = findVideoView(this);
        if (videoView == null || videoView.getMediaPlayer() == null) {
            return true;
        }
        return !(videoView.getMediaPlayer() instanceof xyz.doikki.videoplayer.player.AndroidMediaPlayer);
    }

    private MyVideoView findVideoView(View view) {
        if (view instanceof MyVideoView) {
            return (MyVideoView) view;
        }
        ViewParent parent = view == null ? null : view.getParent();
        while (parent instanceof View) {
            View parentView = (View) parent;
            if (parentView instanceof MyVideoView) {
                return (MyVideoView) parentView;
            }
            if (parentView instanceof ViewGroup) {
                MyVideoView nested = findVideoViewInChildren((ViewGroup) parentView);
                if (nested != null) {
                    return nested;
                }
            }
            parent = parentView.getParent();
        }
        return null;
    }

    private MyVideoView findVideoViewInChildren(ViewGroup group) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof MyVideoView) {
                return (MyVideoView) child;
            }
            if (child instanceof ViewGroup) {
                MyVideoView nested = findVideoViewInChildren((ViewGroup) child);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    @Override
    public boolean onSingleTapConfirmed(MotionEvent e) {
        LOG.i(TAG + " singleTap fullscreen=" + isPlayerFullScreen()
                + " bottomVisible=" + isBottomVisible()
                + " preview=" + embeddedPreviewMode
                + " forceFs=" + forceFullScreenInputMode);
        if (!isPlayerFullScreen()) {
            return enterFullScreenFromPreview();
        }
        if (isJava64TouchPhone() && videoPlayState == VideoView.STATE_PAUSED) {
            LOG.i(TAG + " singleTap resume state=" + videoPlayState);
            if (mControlWrapper != null) {
                mControlWrapper.start();
                mControlWrapper.startProgress();
                mControlWrapper.startFadeOut();
            }
            hideBottom();
            return true;
        }
        myHandle.removeCallbacks(myRunnable);
        if (!isBottomVisible()) {
            showBottom();
            // 闲置计时关闭
            myHandle.postDelayed(myRunnable, myHandleSeconds);
        } else {
            hideBottom();
        }
        return true;
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        if (event != null && isJava64TouchPhone() && isPlayerFullScreen()) {
            setControllerTreeInteractive(true);
        }
        return super.onTouch(v, event);
    }
    
    private class LockRunnable implements Runnable {
        @Override
        public void run() {
            mLockView.setVisibility(INVISIBLE);
        }
    }
    
    @Override
    public boolean onBackPressed() {
        LOG.i(TAG + " onBackPressed fullscreen=" + isPlayerFullScreen() + " bottomVisible=" + isBottomVisible() + " clickBack=" + isClickBackBtn);
        if (isClickBackBtn) {
            isClickBackBtn = false;
            if (isPlayerFullScreen() && isBottomVisible()) {
                hideBottom();
                return true;
            }
            if (isPlayerFullScreen()) {
                hideBottom();
                mControlWrapper.stopFullScreen();
                forceEmbeddedPreviewMode();
                return true;
            }
            if (isBottomVisible()) {
                hideBottom();
                return true;
            }
            return false;
        }
        if (isPlayerFullScreen() && isBottomVisible()) {
            hideBottom();
            return true;
        }
        if (isPlayerFullScreen()) {
            hideBottom();
            mControlWrapper.stopFullScreen();
            forceEmbeddedPreviewMode();
            return true;
        }
        if (super.onBackPressed()) {
            return true;
        }
        if (isBottomVisible()) {
            hideBottom();
            return true;
        }
        return false;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mHandler.removeCallbacks(myRunnable2);
        mHandler.removeCallbacks(seekResumeCheckRunnable);
        mHandler.removeCallbacks(pendingRemoteSeekCommitRunnable);
    }


    //尝试去bom
    public String getWebPlayUrlIfNeeded(String webPlayUrl) {
        return getWebPlayUrlIfNeeded(webPlayUrl, null);
    }

    public String getWebPlayUrlIfNeeded(String webPlayUrl, HashMap<String, String> headers) {
        if (webPlayUrl != null && !webPlayUrl.contains("127.0.0.1:9978") &&  webPlayUrl.contains(".m3u8")) {
            try {
                String urlEncode = URLEncoder.encode(webPlayUrl, "UTF-8");
                LOG.i("echo-BOM-------");
                StringBuilder builder = new StringBuilder(ControlManager.get().getAddress(true))
                        .append("proxy?go=bom&url=")
                        .append(urlEncode);
                if (headers != null && !headers.isEmpty()) {
                    builder.append(PlaybackUrlNormalizer.encodeHeadersQuery(headers));
                }
                return builder.toString();
            } catch (UnsupportedEncodingException e) {
                e.printStackTrace();
            }
        }
        return webPlayUrl;
    }

    public String encodeUrl(String url) {
        try {
            return URLEncoder.encode(url, "UTF-8");
        } catch (Exception e) {
            return url;
        }
    }

    public boolean switchPlayer(){
        try {
            mPlayerConfig.put("pl", PlayerHelper.PLAYER_TYPE_SYSTEM);
            updatePlayerCfgView();
            LOG.i("echo-switchPlayer: system-player-only");
            Toast.makeText(getContext(), "系统硬解播放器重试中", Toast.LENGTH_SHORT).show();
        }catch (Exception e){
            LOG.i("echo-switchPlayer error: " + e.getMessage());
            return true;
        }
        return true;
    }

    public void playM3u8(final String url, final HashMap<String, String> headers) {
        if(url.contains("url=")){
            listener.startPlayUrl(url, headers);
            return;
        }
        OkGo.getInstance().cancelTag("m3u8-1");
        OkGo.getInstance().cancelTag("m3u8-2");
        final HttpHeaders okGoHeaders = new HttpHeaders();
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                okGoHeaders.put(entry.getKey(), entry.getValue());
            }
        }
        OkGo.<String>get(url)
                .tag("m3u8-1")
                .headers(okGoHeaders)
                .execute(new AbsCallback<String>() {
                    @Override
                    public void onSuccess(Response<String> response) {
                        String content = response.body();
                        if (!content.startsWith("#EXTM3U")) {
                            listener.startPlayUrl(url, headers);
                            return;
                        }
                        String forwardUrl = extractForwardUrl(url, content);
                        if (forwardUrl.isEmpty()) {
                            LOG.i("echo-m3u81-to-play");
                            processM3u8Content(url, content, headers);
                        } else {
                            fetchAndProcessForwardUrl(forwardUrl, headers, okGoHeaders, url);
                        }
                    }

                    @Override
                    public String convertResponse(okhttp3.Response response) throws Throwable {
                        return response.body().string();
                    }

                    @Override
                    public void onError(Response<String> response) {
                        super.onError(response);
                        LOG.e("echo-m3u8请求错误1: " + response.getException());
                        listener.startPlayUrl(url, headers);
                    }
                });
    }

    private String extractForwardUrl(String baseUrl, String content) {
        String[] lines = content.split("\\r?\\n",50);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                // 只需要找接下来的几行
                for (int j = i + 1; j < lines.length; j++) {
                    String targetLine = lines[j].trim();
                    if (targetLine.isEmpty()) continue;
                    if (isValidM3u8Line(targetLine)) {
                        return resolveForwardUrl(baseUrl, targetLine);
                    }
                }
            }
        }
        return "";
    }

    private boolean isValidM3u8Line(String line) {
        return !line.startsWith("#") && (line.endsWith(".m3u8") || line.contains(".m3u8?"));
    }

    private void processM3u8Content(String url, String content, HashMap<String, String> headers) {
        String basePath = getBasePath(url);
        RemoteServer.m3u8Content = M3u8.purify(basePath, content);
        if (RemoteServer.m3u8Content == null || M3u8.currentAdCount==0) {
            LOG.i("echo-m3u8内容解析：未检测到广告");
            listener.startPlayUrl(url, headers);
        } else {
            listener.startPlayUrl(ControlManager.get().getAddress(true) + "proxyM3u8", headers);
            Toast.makeText(getContext(), "已移除视频广告 "+M3u8.currentAdCount+" 条", Toast.LENGTH_SHORT).show();
        }
    }

    private void fetchAndProcessForwardUrl(final String forwardUrl, final HashMap<String, String> headers,
                                           HttpHeaders okGoHeaders, final String fallbackUrl) {
        OkGo.<String>get(forwardUrl)
                .tag("m3u8-2")
                .headers(okGoHeaders)
                .execute(new AbsCallback<String>() {
                    @Override
                    public void onSuccess(Response<String> response) {
                        String content = response.body();
                        LOG.i("echo-m3u82-to-play");
                        processM3u8Content(forwardUrl, content, headers);
                    }
                    @Override
                    public String convertResponse(okhttp3.Response response) throws Throwable {
                        return response.body().string();
                    }
                    @Override
                    public void onError(Response<String> response) {
                        super.onError(response);
                        LOG.e("echo-重定向 m3u8 请求错误: " + response.getException());
                        listener.startPlayUrl(fallbackUrl, headers);
                    }
                });
    }

    private String getBasePath(String url) {
        int ilast = url.lastIndexOf('/');
        return url.substring(0, ilast + 1);
    }

    private String resolveForwardUrl(String baseUrl, String line) {
        try {
            // 使用 URL 构造器自动解析相对路径
            URL base = new URL(baseUrl);
            URL resolved = new URL(base, line);
            return resolved.toString();
        } catch (MalformedURLException e) {
            // 出现异常时可以记录日志，并返回原始 line
            LOG.e("echo-resolveForwardUrl异常: " + e.getMessage());
            return line;
        }
    }

    public String firstUrlByArray(String url)
    {
        try {
            JSONArray urlArray = new JSONArray(url);
            if (urlArray.length() > 0) {
                String firstItem = urlArray.optString(0, url);
                LOG.i("echo-play-array strict-first count=" + urlArray.length());
                return firstItem;
            }
        } catch (JSONException e) {
            LOG.i("echo-play-array parse-failed " + e.getMessage());
        }
        return url;
    }

    public void evaluateScript(SourceBean sourceBean,String url, WebView web_view, XWalkView xWalk_view){
        String clickSelector = sourceBean.getClickSelector().trim();
        clickSelector=clickSelector.isEmpty()?VideoParseRuler.getHostScript(url):clickSelector;
        if (!clickSelector.isEmpty()) {
            String selector;
            if (clickSelector.contains(";") && !clickSelector.endsWith(";")) {
                String[] parts = clickSelector.split(";", 2);
                if (!url.contains(parts[0])) {
                    return;
                }
                selector = parts[1].trim();
            } else {
                selector = clickSelector.trim();
            }
            // 构造点击的 JS 代码
            String js = selector;
//            if(!selector.contains("click()"))js+=".click();";
            LOG.i("echo-javascript:" + js);
            if(web_view!=null){
                //4.4以上才支持这种写法
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                    web_view.evaluateJavascript(js, null);
                } else {
                    web_view.loadUrl("javascript:" + js);
                }
            }
            if(xWalk_view!=null){
                //4.0+开始全部支持这种写法
                xWalk_view.evaluateJavascript(js, null);
            }
        }
    }

    public void stopOther()
    {
        Thunder.stop(false);//停止磁力下载
        Jianpian.finish();//停止p2p下载
        App.getInstance().setDashData(null);
    }
}
