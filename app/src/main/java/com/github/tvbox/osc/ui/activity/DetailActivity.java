package com.github.tvbox.osc.ui.activity;

import android.annotation.SuppressLint;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PointF;
import android.graphics.Rect;
import android.os.Bundle;
import android.text.Html;
import android.text.TextUtils;
import android.util.Log;
import android.util.DisplayMetrics;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.content.ClipboardManager;
import android.content.ClipData;

import androidx.fragment.app.FragmentContainerView;
import androidx.core.view.ViewCompat;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearSmoothScroller;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.base.BaseActivity;
import com.github.tvbox.osc.bean.AbsXml;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.cache.RoomDataManger;
import com.github.tvbox.osc.event.RefreshEvent;
import com.github.tvbox.osc.ui.adapter.SeriesAdapter;
import com.github.tvbox.osc.ui.adapter.SeriesFlagAdapter;
import com.github.tvbox.osc.ui.dialog.DescDialog;
import com.github.tvbox.osc.ui.dialog.QuickSearchDialog;
import com.github.tvbox.osc.ui.fragment.PlayFragment;
import com.github.tvbox.osc.util.DefaultConfig;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.SearchHelper;
import com.github.tvbox.osc.util.PlaybackHandoff;
import com.github.tvbox.osc.util.SubtitleHelper;
import com.github.tvbox.osc.viewmodel.SourceViewModel;
import com.lzy.okgo.OkGo;
import com.orhanobut.hawk.Hawk;
import com.owen.tvrecyclerview.widget.TvRecyclerView;
import com.owen.tvrecyclerview.widget.V7GridLayoutManager;
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager;
import com.squareup.picasso.Picasso;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


import android.graphics.Paint;

/**
 * @author pj567
 * @date :2020/12/22
 * @description:
 */

public class DetailActivity extends BaseActivity {
    private static final String TAG = "DetailActivity";
    private LinearLayout llLayout;
    private FragmentContainerView llPlayerFragmentContainer;
    private View llPlayerFragmentContainerBlock;
    private View llPlayerPlace;
    private View mediaColumn;
    private PlayFragment playFragment = null;
    private boolean detailPlaybackStopped;
    private ImageView ivThumb;
    private TextView tvName;
    private TextView tvYear;
    private TextView tvSite;
    private TextView tvArea;
    private TextView tvLang;
    private TextView tvType;
    private TextView tvActor;
    private TextView tvDirector;
    private TextView tvPlayUrl;
    private TextView tvPlayingFileName;
    private TextView tvDes;
    private TextView tvPlay;
//    private TextView tvSort;
    private TextView tvDesc;
    private TextView tvSeriesSort;
    private TextView tvQuickSearch;
    private TextView tvCollect;
    /** Full playback URL retained for the copy action; the detail view only shows a bounded preview. */
    private String currentPlayUrl = "";
    private TvRecyclerView mGridViewFlag;
    private TvRecyclerView mGridView;
    private TvRecyclerView mSeriesGroupView;
    private LinearLayout mEmptyPlayList;
    private LinearLayout tvSeriesGroup;
    private LinearLayout topLayout;
    private LinearLayout detailInfoColumn;
    private LinearLayout detailActionsRow;
    private SourceViewModel sourceViewModel;
    private Movie.Video mVideo;
    private VodInfo vodInfo;
    private SeriesFlagAdapter seriesFlagAdapter;
    private BaseQuickAdapter<String, BaseViewHolder> seriesGroupAdapter;
    private SeriesAdapter seriesAdapter;
    public String vodId;
    public String sourceKey;
    public String firstsourceKey;
    boolean seriesSelect = false;
    private View seriesFlagFocus = null;
    private boolean isReverse;
    private String preFlag="";
    private boolean firstReverse;
    private V7GridLayoutManager mGridViewLayoutMgr = null;
    private HashMap<String, String> mCheckSources = null;
    private final ArrayList<String> seriesGroupOptions = new ArrayList<>();
    private View currentSeriesGroupView;
    private int GroupCount;
    boolean showPreview = Hawk.get(HawkConfig.SHOW_PREVIEW, true); // true 开启 false 关闭
    private int pendingFlagPosition = -1;
    private boolean preferPreviewFocus = true;
    private boolean fullPlayerResolvePending = false;
    private String fullPlayerResolveRequestKey = "";
    private String fullPlayerResolveProgressKey = "";
    private String fullPlayerResolveSourceKey = "";
    private String fullPlayerResolveVodId = "";
    private String fullPlayerResolvePlayFlag = "";
    private int fullPlayerResolvePlayIndex = -1;
    private String fullPlayerResolveSeriesUrl = "";
    private int fullPlayerResolveSerial = 0;
    private ExecutorService playbackResolveExecutor;

    private LinearSmoothScroller smoothScroller;

    @Override
    protected int getLayoutResID() {
        return R.layout.activity_detail;
    }

    @Override
    protected void init() {
        if (isJava64PhoneDevice()) {
            showPreview = false;
        }
        EventBus.getDefault().register(this);
        initView();
        initViewModel();
        initData();
    }

    private void initView() {
        llLayout = findViewById(R.id.llLayout);
        topLayout = findViewById(R.id.topLayout);
        detailInfoColumn = findViewById(R.id.detailInfoColumn);
        detailActionsRow = findViewById(R.id.detailActionsRow);
        llPlayerPlace = findViewById(R.id.previewPlayerPlace);
        llPlayerFragmentContainer = findViewById(R.id.previewPlayer);
        llPlayerFragmentContainerBlock = findViewById(R.id.previewPlayerBlock);
        mediaColumn = findViewById(R.id.mediaColumn);
        llPlayerFragmentContainer.setFocusable(false);
        llPlayerFragmentContainer.setFocusableInTouchMode(false);
        llPlayerPlace.setFocusable(false);
        llPlayerPlace.setFocusableInTouchMode(false);
        ivThumb = findViewById(R.id.ivThumb);
        llPlayerPlace.setVisibility(showPreview ? View.VISIBLE : View.GONE);
        ivThumb.setVisibility(!showPreview ? View.VISIBLE : View.GONE);
        configureTouchDetailLayout();
        tvName = findViewById(R.id.tvName);
        tvYear = findViewById(R.id.tvYear);
        tvSite = findViewById(R.id.tvSite);
        tvArea = findViewById(R.id.tvArea);
        tvLang = findViewById(R.id.tvLang);
        tvType = findViewById(R.id.tvType);
        tvActor = findViewById(R.id.tvActor);
        tvDirector = findViewById(R.id.tvDirector);
        tvPlayUrl = findViewById(R.id.tvPlayUrl);
        tvPlayingFileName = findViewById(R.id.tvPlayingFileName);
        tvDes = findViewById(R.id.tvDes);
        tvPlay = findViewById(R.id.tvPlay);
//        tvSort = findViewById(R.id.tvSort);
        tvDesc = findViewById(R.id.tvDesc);
        tvSeriesSort = findViewById(R.id.mSeriesSortTv);
        tvCollect = findViewById(R.id.tvCollect);
        tvQuickSearch = findViewById(R.id.tvQuickSearch);
        mEmptyPlayList = findViewById(R.id.mEmptyPlaylist);
        mGridView = findViewById(R.id.mGridView);
        mGridView.setHasFixedSize(false);
        this.mGridViewLayoutMgr = new V7GridLayoutManager(this.mContext, 6);
        mGridView.setLayoutManager(this.mGridViewLayoutMgr);
//        mGridView.setLayoutManager(new V7LinearLayoutManager(this.mContext, 0, false));

        smoothScroller = new LinearSmoothScroller(mContext) {
            @Override
            protected float calculateSpeedPerPixel(DisplayMetrics displayMetrics) {
                return 100f / displayMetrics.densityDpi;
            }
            @Override
            public PointF computeScrollVectorForPosition(int targetPosition) {
                return mGridViewLayoutMgr.computeScrollVectorForPosition(targetPosition);
            }
        };

        seriesAdapter = new SeriesAdapter(this.mGridViewLayoutMgr);
        mGridView.setAdapter(seriesAdapter);
        mGridViewFlag = findViewById(R.id.mGridViewFlag);
        mGridViewFlag.setHasFixedSize(true);
        mGridViewFlag.setLayoutManager(new V7LinearLayoutManager(this.mContext, 0, false));
        seriesFlagAdapter = new SeriesFlagAdapter();
        mGridViewFlag.setAdapter(seriesFlagAdapter);
        isReverse = false;
        firstReverse = false;
        preFlag = "";
        if (showPreview) {
            playFragment = new PlayFragment();
            getSupportFragmentManager().beginTransaction().add(R.id.previewPlayer, playFragment).commit();
            getSupportFragmentManager().beginTransaction().show(playFragment).commitAllowingStateLoss();
            tvPlay.setText("全屏");
            llPlayerFragmentContainer.setVisibility(View.VISIBLE);
            llPlayerFragmentContainerBlock.setVisibility(View.VISIBLE);
        }
        updatePreviewBlockFocusability(showPreview && llPlayerFragmentContainerBlock.getVisibility() == View.VISIBLE);

        mSeriesGroupView = findViewById(R.id.mSeriesGroupView);
        tvSeriesGroup = findViewById(R.id.mSeriesGroupTv);
        mSeriesGroupView.setHasFixedSize(true);
        mSeriesGroupView.setLayoutManager(new V7LinearLayoutManager(this.mContext, 0, false));
        seriesGroupAdapter = new BaseQuickAdapter<String, BaseViewHolder>(R.layout.item_series_group, seriesGroupOptions) {
            @Override
            protected void convert(BaseViewHolder helper, String item) {
                TextView tvSeries = helper.getView(R.id.tvSeriesGroup);
                tvSeries.setText(item);
                if (helper.getLayoutPosition() == getData().size() - 1) {
                    helper.itemView.setId(View.generateViewId());
                    helper.itemView.setNextFocusRightId(helper.itemView.getId());
                }else {
                    helper.itemView.setNextFocusRightId(View.NO_ID);
                }
            }
        };
        mSeriesGroupView.setAdapter(seriesGroupAdapter);

        llPlayerFragmentContainerBlock.setOnClickListener(v -> {
            openFullPlayer();
            firstReverse = false;
        });

        tvPlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                if (showPreview) {
                    openFullPlayer();
                    firstReverse = false;
                } else {
                    openFullPlayer();
                }
            }
        });
        bindButtonFocus(tvPlay);

        tvQuickSearch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startQuickSearch();
                QuickSearchDialog quickSearchDialog = new QuickSearchDialog(DetailActivity.this);
                EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_QUICK_SEARCH, quickSearchData));
                EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_QUICK_SEARCH_WORD, quickSearchWord));
                quickSearchDialog.show();
                if (pauseRunnable != null && pauseRunnable.size() > 0) {
                    searchExecutorService = Executors.newFixedThreadPool(5);
                    for (Runnable runnable : pauseRunnable) {
                        searchExecutorService.execute(runnable);
                    }
                    pauseRunnable.clear();
                    pauseRunnable = null;
                }
                quickSearchDialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override
                    public void onDismiss(DialogInterface dialog) {
                        try {
                            if (searchExecutorService != null) {
                                pauseRunnable = searchExecutorService.shutdownNow();
                                searchExecutorService = null;
                            }
                        } catch (Throwable th) {
                            th.printStackTrace();
                        }
                    }
                });
            }
        });
        bindButtonFocus(tvQuickSearch);
        tvCollect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String text = tvCollect.getText().toString();
                if ("加入收藏".equals(text)) {
                    RoomDataManger.insertVodCollect(sourceKey, vodInfo);
                    Toast.makeText(DetailActivity.this, "已加入收藏夹", Toast.LENGTH_SHORT).show();
                    tvCollect.setText("取消收藏");
                } else {
                    RoomDataManger.deleteVodCollect(sourceKey, vodInfo);
                    Toast.makeText(DetailActivity.this, "已移除收藏夹", Toast.LENGTH_SHORT).show();
                    tvCollect.setText("加入收藏");
                }
            }
        });
        bindButtonFocus(tvCollect);
        tvPlayUrl.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                //获取剪切板管理器
                ClipboardManager cm = (ClipboardManager)getSystemService(mContext.CLIPBOARD_SERVICE);
                //设置内容到剪切板
                cm.setPrimaryClip(ClipData.newPlainText(null, currentPlayUrl));
                Toast.makeText(DetailActivity.this, "已复制", Toast.LENGTH_SHORT).show();
            }
        });


        tvSeriesSort.setOnClickListener(new View.OnClickListener() {
            @SuppressLint("NotifyDataSetChanged")
            @Override
            public void onClick(View v) {
                if (vodInfo != null && vodInfo.seriesMap.size() > 0) {
                    vodInfo.reverseSort = !vodInfo.reverseSort;
                    isReverse = !isReverse;
                    tvSeriesSort.setText(isReverse?"倒序":"正序");
                    vodInfo.reverse();
                    vodInfo.playIndex=(vodInfo.seriesMap.get(vodInfo.playFlag).size()-1)-vodInfo.playIndex;
                    firstReverse = !firstReverse;
                    setSeriesGroupOptions();
                    seriesAdapter.notifyDataSetChanged();

                    customSeriesScrollPos(vodInfo.playIndex);
                    if(currentSeriesGroupView != null) {
                        TextView txtView = currentSeriesGroupView.findViewById(R.id.tvSeriesGroup);
                        txtView.setTextColor(Color.WHITE);
                    }
                }
            }
        });
        tvDesc.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        FastClickCheckUtil.check(v);
                        DescDialog dialog = new DescDialog(mContext);
                        dialog.setDescribe(removeHtmlTag(mVideo.des));
                        dialog.show();
                    }
                });
            }
        });
        bindButtonFocus(tvDesc);

        mGridView.setOnItemListener(new TvRecyclerView.OnItemListener() {
            @Override
            public void onItemPreSelected(TvRecyclerView parent, View itemView, int position) {
                seriesSelect = false;
            }

            @Override
            public void onItemSelected(TvRecyclerView parent, View itemView, int position) {
                seriesSelect = true;
            }

            @Override
            public void onItemClick(TvRecyclerView parent, View itemView, int position) {
            }
        });
        mGridViewFlag.setOnItemListener(new TvRecyclerView.OnItemListener() {
            private void updatePendingFlagFocus(View itemView, int position) {
                pendingFlagPosition = position;
                seriesFlagFocus = itemView;
            }

            @Override
            public void onItemPreSelected(TvRecyclerView parent, View itemView, int position) {
//                seriesSelect = false;
            }

            @Override
            public void onItemSelected(TvRecyclerView parent, View itemView, int position) {
                updatePendingFlagFocus(itemView, position);
            }

            @Override
            public void onItemClick(TvRecyclerView parent, View itemView, int position) {
                applySeriesFlagSelection(position);
            }
        });
        seriesAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                FastClickCheckUtil.check(view);
                if (hasValidSelection()) {
                    boolean reload = false;
                    boolean isAllowFull = false;
                    for (int j = 0; j < vodInfo.seriesMap.get(vodInfo.playFlag).size(); j++) {
                        seriesAdapter.getData().get(j).selected = false;
                        seriesAdapter.notifyItemChanged(j);
                    }
                    //解决倒叙不刷新
                    if (vodInfo.playIndex != position) {
                        seriesAdapter.getData().get(position).selected = true;
                        seriesAdapter.notifyItemChanged(position);
                        vodInfo.playIndex = position;

                        reload = true;
                    }
                    //解决当前集不刷新的BUG
                    if (!preFlag.isEmpty() && !vodInfo.playFlag.equals(preFlag)) {
                        reload = true;
                        isAllowFull = true;
                    }

                    seriesAdapter.getData().get(vodInfo.playIndex).selected = true;
                    seriesAdapter.notifyItemChanged(vodInfo.playIndex);
                    if (!showPreview || reload) {
                        playCurrentSelection(showPreview, false);
                        firstReverse=false;
                    }
                }
            }
        });

        mSeriesGroupView.setOnItemListener(new TvRecyclerView.OnItemListener() {
            @Override
            public void onItemPreSelected(TvRecyclerView parent, View itemView, int position) {
                TextView txtView = itemView.findViewById(R.id.tvSeriesGroup);
                txtView.setTextColor(mContext.getResources().getColor(R.color.apple_tv_text_primary));
//                currentSeriesGroupView = null;
            }

            @Override
            public void onItemSelected(TvRecyclerView parent, View itemView, int position) {
                TextView txtView = itemView.findViewById(R.id.tvSeriesGroup);
                txtView.setTextColor(mContext.getResources().getColor(R.color.apple_tv_chip_text_active));
                if (vodInfo != null && vodInfo.seriesMap != null
                        && vodInfo.seriesMap.get(vodInfo.playFlag) != null
                        && !vodInfo.seriesMap.get(vodInfo.playFlag).isEmpty()) {
                    int targetPos = position * GroupCount;
//                    mGridView.smoothScrollToPosition(targetPos);
                    customSeriesScrollPos(targetPos);
                }
                currentSeriesGroupView = itemView;
                currentSeriesGroupView.isSelected();
            }

            @Override
            public void onItemClick(TvRecyclerView parent, View itemView, int position) { }
        });
        tvSeriesSort.setOnFocusChangeListener((view, hasFocus) -> {
            if (hasFocus) {
                tvSeriesSort.setTextColor(mContext.getResources().getColor(R.color.apple_tv_chip_text_active));
                if (vodInfo != null && Objects.requireNonNull(vodInfo.seriesMap.get(vodInfo.playFlag)).size() > 0) {
                    int firstVisible = mGridView.getFirstVisiblePosition();
                    int lastVisible = mGridView.getLastVisiblePosition();
                    if (vodInfo.playIndex < firstVisible || vodInfo.playIndex > lastVisible) {
                        customSeriesScrollPos(vodInfo.playIndex);
                    }
                }
            } else {
                tvSeriesSort.setTextColor(mContext.getResources().getColor(R.color.apple_tv_text_primary));
            }
        });
        seriesGroupAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                FastClickCheckUtil.check(view);
                TextView newTxtView = view.findViewById(R.id.tvSeriesGroup);
                newTxtView.setTextColor(mContext.getResources().getColor(R.color.apple_tv_chip_text_active));
                if (vodInfo != null && vodInfo.seriesMap.get(vodInfo.playFlag).size() > 0) {
                    int targetPos =  position * GroupCount+1;

                    customSeriesScrollPos(targetPos);
                }
                if(currentSeriesGroupView != null) {
                    TextView txtView = currentSeriesGroupView.findViewById(R.id.tvSeriesGroup);
                    txtView.setTextColor(mContext.getResources().getColor(R.color.apple_tv_text_primary));
                }
                currentSeriesGroupView = view;
                currentSeriesGroupView.isSelected();
            }
        });

        if(showPreview){
            requestPreviewFocus();
        }else {
            tvPlay.requestFocus();
        }
        setLoadSir(llLayout);
    }

    /**
     * The phone build intentionally disables the embedded preview. Keep the
     * detail actions in the viewport instead of retaining the TV-only fixed
     * poster width and placing the action column off the right edge.
     */
    private void configureTouchDetailLayout() {
        if (!isJava64PhoneDevice() || topLayout == null || detailInfoColumn == null || mediaColumn == null) {
            return;
        }
        topLayout.setOrientation(LinearLayout.HORIZONTAL);
        topLayout.setClipChildren(false);
        topLayout.setClipToPadding(false);
        detailInfoColumn.setClipChildren(false);
        detailInfoColumn.setClipToPadding(false);
        ViewGroup.LayoutParams posterParams = mediaColumn.getLayoutParams();
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        posterParams.width = Math.max(1, Math.round(screenWidth * 0.27f));
        posterParams.height = Math.max(1, Math.round(screenHeight * 0.35f));
        mediaColumn.setLayoutParams(posterParams);
        // Keep the top panel tied to the poster's measured height.  A fixed
        // XML height is appropriate for the TV layout, but would make the
        // touch/phone layout taller than the viewport and push the playlist
        // and action controls off screen.
        LinearLayout.LayoutParams topParams = (LinearLayout.LayoutParams) topLayout.getLayoutParams();
        topParams.height = posterParams.height + topLayout.getPaddingTop() + topLayout.getPaddingBottom();
        topParams.weight = 0f;
        topLayout.setLayoutParams(topParams);
        LinearLayout.LayoutParams infoParams = (LinearLayout.LayoutParams) detailInfoColumn.getLayoutParams();
        infoParams.width = 0;
        // The java64 touch layout gives the top panel a weighted height and
        // the info column fills that panel. Using wrap_content here lets the
        // description/actions extend past the weighted panel and be clipped
        // by its parent. Keep the original match-parent contract for that
        // layout, while retaining wrap-content for the main TV layout.
        infoParams.height = ViewGroup.LayoutParams.MATCH_PARENT;
        infoParams.weight = 1f;
        detailInfoColumn.setLayoutParams(infoParams);
        if (detailActionsRow != null) {
            int buttonHeight = Math.max(1, Math.round(screenHeight * 0.085f));
            detailActionsRow.setVisibility(View.VISIBLE);
            detailActionsRow.setClipChildren(false);
            detailActionsRow.setClipToPadding(false);
            int[] buttonIds = {R.id.tvPlay, R.id.tvQuickSearch, R.id.tvDesc, R.id.tvCollect};
            for (int id : buttonIds) {
                View button = detailActionsRow.findViewById(id);
                if (button == null) {
                    continue;
                }
                LinearLayout.LayoutParams buttonParams = (LinearLayout.LayoutParams) button.getLayoutParams();
                // Let the row divide its measured width evenly. Fixed pixel widths
                // combined with the XML weight caused the rightmost controls to be
                // laid out beyond the info column on portrait and split-screen phones.
                buttonParams.width = 0;
                buttonParams.height = buttonHeight;
                buttonParams.weight = 1f;
                button.setLayoutParams(buttonParams);
            }
        }
    }

    //解决类似海贼王的超长动漫 焦点滚动失败的问题
    void customSeriesScrollPos(int targetPos)
    {
        mGridViewLayoutMgr.scrollToPositionWithOffset(targetPos>10?targetPos - 10:0, 0);
        mGridView.postDelayed(() -> {
            this.smoothScroller.setTargetPosition(targetPos);
            mGridViewLayoutMgr.startSmoothScroll(smoothScroller);
            mGridView.smoothScrollToPosition(targetPos);
        }, 50);
    }

    private void initCheckedSourcesForSearch() {
        mCheckSources = SearchHelper.getSourcesForSearch();
    }

    private List<Runnable> pauseRunnable = null;

    private void playCurrentSelection(boolean usePreviewPlayer, boolean openPlayerActivity) {
        if (detailPlaybackStopped || isFinishing()) {
            return;
        }
        if (fullPlayerResolvePending) {
            // A new episode/source selection supersedes any QR/playerContent request still
            // waiting for approval. Its callback must never launch the old episode.
            fullPlayerResolvePending = false;
            fullPlayerResolveSerial++;
            if (sourceViewModel != null) {
                sourceViewModel.cancelPlayRequests();
            }
        }
        List<VodInfo.VodSeries> selectedEpisodes = vodInfo == null || vodInfo.seriesMap == null
                ? null : vodInfo.seriesMap.get(vodInfo.playFlag);
        if (vodInfo != null && selectedEpisodes != null && !selectedEpisodes.isEmpty()
                && vodInfo.playIndex >= 0 && vodInfo.playIndex < selectedEpisodes.size()
                && selectedEpisodes.get(vodInfo.playIndex) != null
                && !TextUtils.isEmpty(selectedEpisodes.get(vodInfo.playIndex).url)) {
            preFlag = vodInfo.playFlag;
            setCurrentPlayingSeries(vodInfo.seriesMap.get(vodInfo.playFlag).get(vodInfo.playIndex));
            Bundle bundle = new Bundle();
            String activeSourceKey = TextUtils.isEmpty(sourceKey) ? firstsourceKey : sourceKey;
            vodInfo.sourceKey = activeSourceKey;
            try {
                insertVod(activeSourceKey, vodInfo);
            } catch (Throwable error) {
                // A history write cannot prevent a selected episode/source from loading.
                LOG.e("echo-detail-history-save failed: " + error.getClass().getSimpleName());
            }
            // PlayActivity runs in the dedicated :player process.  App.vodInfo is
            // process-local, so relying on that singleton loses the selected title
            // as soon as the full-screen player is launched.  Pass the immutable
            // playback snapshot explicitly and use the key we actually selected.
            bundle.putString("sourceKey", activeSourceKey);
            if (usePreviewPlayer && !openPlayerActivity) {
                // Same-process preview can safely use the object fallback if the cache write fails.
                bundle.putSerializable("VodInfo", vodInfo);
                bundle.putSerializable("SourceBean", ApiConfig.get().getSource(activeSourceKey));
            }
            App.getInstance().setVodInfo(vodInfo);
            if (usePreviewPlayer && playFragment != null && !openPlayerActivity) {
                if (previewVodInfo == null) {
                    try {
                        ByteArrayOutputStream bos = new ByteArrayOutputStream();
                        ObjectOutputStream oos = new ObjectOutputStream(bos);
                        oos.writeObject(vodInfo);
                        oos.flush();
                        oos.close();
                        ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(bos.toByteArray()));
                        previewVodInfo = (VodInfo) ois.readObject();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
                if (previewVodInfo != null) {
                    previewVodInfo.playerCfg = vodInfo.playerCfg;
                    previewVodInfo.playFlag = vodInfo.playFlag;
                    previewVodInfo.playIndex = vodInfo.playIndex;
                    previewVodInfo.seriesMap = vodInfo.seriesMap;
                    App.getInstance().setVodInfo(previewVodInfo);
                }
                playFragment.setData(bundle);
            } else {
                beginFullPlayerResolve(activeSourceKey, vodInfo,
                        selectedEpisodes.get(vodInfo.playIndex));
            }
        }
    }

    /** Resolve Spider/QR-backed playerContent while this UI is still alive. */
    private void beginFullPlayerResolve(String activeSource, VodInfo selectedVod,
                                        VodInfo.VodSeries selectedSeries) {
        if (sourceViewModel == null || selectedVod == null || selectedSeries == null
                || TextUtils.isEmpty(selectedSeries.url)) {
            Toast.makeText(this, "播放信息无效，请重试", Toast.LENGTH_SHORT).show();
            return;
        }
        fullPlayerResolveSerial++;
        fullPlayerResolvePending = true;
        fullPlayerResolveSourceKey = activeSource;
        fullPlayerResolveVodId = selectedVod.id;
        fullPlayerResolvePlayFlag = selectedVod.playFlag;
        fullPlayerResolvePlayIndex = selectedVod.playIndex;
        fullPlayerResolveSeriesUrl = selectedSeries.url;
        fullPlayerResolveRequestKey = activeSource + "|" + selectedVod.id + "|"
                + selectedVod.playFlag + "|" + selectedVod.playIndex + "|"
                + selectedSeries.name + "|" + selectedSeries.url;
        fullPlayerResolveProgressKey = activeSource + "|" + selectedVod.id + "|"
                + selectedVod.playFlag + "|" + selectedVod.playIndex;
        String subtitleKey = activeSource + "-" + selectedVod.id + "-"
                + selectedVod.playFlag + "-" + selectedVod.playIndex + "-"
                + selectedSeries.name + "-subt";
        LOG.i("echo-detail-resolve-full-player source=" + activeSource
                + " flag=" + selectedVod.playFlag + " index=" + selectedVod.playIndex);
        sourceViewModel.getPlay(activeSource, selectedVod.playFlag, fullPlayerResolveProgressKey,
                selectedSeries.url, subtitleKey, fullPlayerResolveRequestKey);
        Toast.makeText(this, "正在准备播放", Toast.LENGTH_SHORT).show();
    }

    private void handleFullPlayerResolveResult(JSONObject info) {
        if (!fullPlayerResolvePending) {
            return;
        }
        if (info == null) {
            fullPlayerResolvePending = false;
            Toast.makeText(this, "授权或播放地址获取失败，请取消后重试", Toast.LENGTH_SHORT).show();
            return;
        }
        String requestKey = info.optString("reqKey", "");
        String progressKey = info.optString("proKey", "");
        if (!TextUtils.equals(requestKey, fullPlayerResolveRequestKey)
                || !TextUtils.equals(progressKey, fullPlayerResolveProgressKey)) {
            LOG.i("echo-detail-resolve-drop-stale");
            return;
        }
        String url = info.optString("url", "").trim();
        String error = info.optString("errMsg", info.optString("error", ""));
        if (TextUtils.isEmpty(url) || !info.optBoolean("playable", false)
                || !TextUtils.isEmpty(error)) {
            fullPlayerResolvePending = false;
            Toast.makeText(this, "授权或播放地址获取失败，请取消后重试", Toast.LENGTH_SHORT).show();
            return;
        }
        if (vodInfo == null || !TextUtils.equals(vodInfo.id, fullPlayerResolveVodId)
                || !TextUtils.equals(vodInfo.playFlag, fullPlayerResolvePlayFlag)
                || vodInfo.playIndex != fullPlayerResolvePlayIndex
                || !TextUtils.equals(resolveCurrentSourceKey(), fullPlayerResolveSourceKey)
                || vodInfo.seriesMap == null
                || vodInfo.seriesMap.get(vodInfo.playFlag) == null
                || vodInfo.playIndex < 0
                || vodInfo.playIndex >= vodInfo.seriesMap.get(vodInfo.playFlag).size()
                || !TextUtils.equals(vodInfo.seriesMap.get(vodInfo.playFlag)
                .get(vodInfo.playIndex).url, fullPlayerResolveSeriesUrl)) {
            fullPlayerResolvePending = false;
            LOG.i("echo-detail-resolve-drop-selection-changed");
            return;
        }
        final int serial = fullPlayerResolveSerial;
        // The handoff serializer runs off the UI thread. The selection identity is
        // rechecked before launch, so a mutable detail object cannot start a stale item.
        final VodInfo handoffVod = vodInfo;
        final String handoffSource = fullPlayerResolveSourceKey;
        final SourceBean handoffSourceBean = ApiConfig.get().getSource(handoffSource);
        final String resolvedJson = info.toString();
        if (handoffSourceBean == null) {
            fullPlayerResolvePending = false;
            Toast.makeText(this, "播放数据准备失败，请重试", Toast.LENGTH_SHORT).show();
            return;
        }
        if (playbackResolveExecutor != null) {
            playbackResolveExecutor.shutdownNow();
        }
        final ExecutorService resolveExecutor = Executors.newSingleThreadExecutor();
        playbackResolveExecutor = resolveExecutor;
        resolveExecutor.execute(() -> {
            String handoffKey = PlaybackHandoff.write(handoffVod, handoffSourceBean, resolvedJson);
            runOnUiThread(() -> {
                // A stale serializer callback must only close its own executor. A newer
                // request may already have replaced the field while this background write
                // was finishing; closing the field here strands that newer request.
                resolveExecutor.shutdown();
                if (playbackResolveExecutor == resolveExecutor) {
                    playbackResolveExecutor = null;
                }
                if (serial != fullPlayerResolveSerial || !fullPlayerResolvePending
                        || !isResolveSelectionCurrent()
                        || isFinishing() || (android.os.Build.VERSION.SDK_INT >= 17 && isDestroyed())) {
                    PlaybackHandoff.delete(handoffKey);
                    return;
                }
                if (TextUtils.isEmpty(handoffKey)) {
                    fullPlayerResolvePending = false;
                    Toast.makeText(this, "播放数据准备失败，请重试", Toast.LENGTH_SHORT).show();
                    return;
                }
                Bundle bundle = new Bundle();
                bundle.putString("sourceKey", handoffSource);
                bundle.putString("playbackHandoffKey", handoffKey);
                App.getInstance().setVodInfo(handoffVod);
                fullPlayerResolvePending = false;
                LOG.i("echo-detail-resolve-success launch-player");
                jumpActivity(PlayActivity.class, bundle);
            });
        });
    }

    private boolean isResolveSelectionCurrent() {
        if (vodInfo == null || !TextUtils.equals(vodInfo.id, fullPlayerResolveVodId)
                || !TextUtils.equals(vodInfo.playFlag, fullPlayerResolvePlayFlag)
                || vodInfo.playIndex != fullPlayerResolvePlayIndex
                || !TextUtils.equals(resolveCurrentSourceKey(), fullPlayerResolveSourceKey)
                || vodInfo.seriesMap == null) {
            return false;
        }
        List<VodInfo.VodSeries> episodes = vodInfo.seriesMap.get(vodInfo.playFlag);
        return episodes != null && vodInfo.playIndex >= 0 && vodInfo.playIndex < episodes.size()
                && episodes.get(vodInfo.playIndex) != null
                && TextUtils.equals(episodes.get(vodInfo.playIndex).url, fullPlayerResolveSeriesUrl);
    }

    private boolean hasValidSelection() {
        if (vodInfo == null || vodInfo.seriesMap == null || TextUtils.isEmpty(vodInfo.playFlag)) {
            return false;
        }
        List<VodInfo.VodSeries> episodes = vodInfo.seriesMap.get(vodInfo.playFlag);
        return episodes != null && !episodes.isEmpty()
                && vodInfo.playIndex >= 0 && vodInfo.playIndex < episodes.size()
                && episodes.get(vodInfo.playIndex) != null
                && !TextUtils.isEmpty(episodes.get(vodInfo.playIndex).url);
    }

    private String resolveCurrentSourceKey() {
        return TextUtils.isEmpty(sourceKey) ? firstsourceKey : sourceKey;
    }

    private void openFullPlayer() {
        if (showPreview && playFragment != null) {
            if (playFragment.enterEmbeddedFullScreen()) {
                playFragment.syncEmbeddedControllerMode();
                schedulePreviewBlockSync();
            }
            return;
        }
        playCurrentSelection(false, true);
    }

    @SuppressLint("NotifyDataSetChanged")
    private void applySeriesFlagSelection(int position) {
        if (vodInfo == null || vodInfo.seriesFlags == null || position < 0 || position >= vodInfo.seriesFlags.size()) {
            return;
        }
        String newFlag = vodInfo.seriesFlags.get(position).name;
        if (TextUtils.isEmpty(newFlag) || TextUtils.equals(vodInfo.playFlag, newFlag)) {
            pendingFlagPosition = position;
            return;
        }
        String oldFlag = vodInfo.playFlag;
        int oldIndex = Math.max(vodInfo.playIndex, 0);
        VodInfo.VodSeries currentSeries = null;
        List<VodInfo.VodSeries> oldSeriesList = vodInfo.seriesMap.get(oldFlag);
        if (oldSeriesList != null && !oldSeriesList.isEmpty()) {
            int safeOldIndex = Math.max(0, Math.min(oldIndex, oldSeriesList.size() - 1));
            currentSeries = oldSeriesList.get(safeOldIndex);
            oldSeriesList.get(safeOldIndex).selected = false;
        }
        for (int i = 0; i < vodInfo.seriesFlags.size(); i++) {
            vodInfo.seriesFlags.get(i).selected = i == position;
        }
        vodInfo.playFlag = newFlag;
        List<VodInfo.VodSeries> newSeriesList = vodInfo.seriesMap.get(newFlag);
        if (newSeriesList != null && !newSeriesList.isEmpty()) {
            vodInfo.playIndex = findSameEpisodeIndex(currentSeries, newSeriesList, oldIndex);
            for (VodInfo.VodSeries series : newSeriesList) {
                series.selected = false;
            }
            newSeriesList.get(vodInfo.playIndex).selected = true;
        } else {
            vodInfo.playIndex = 0;
        }
        seriesFlagAdapter.notifyDataSetChanged();
        refreshList();
        mGridView.clearFocus();
        pendingFlagPosition = position;
    }

    @SuppressLint("NotifyDataSetChanged")
    void refreshList() {
        if (vodInfo.seriesMap.get(vodInfo.playFlag).size() <= vodInfo.playIndex) {
            vodInfo.playIndex = 0;
        }

        if (vodInfo.seriesMap.get(vodInfo.playFlag) != null) {
            List<VodInfo.VodSeries> currentSeries = vodInfo.seriesMap.get(vodInfo.playFlag);
            for (int j = 0; j < currentSeries.size(); j++) {
                currentSeries.get(j).selected = j == vodInfo.playIndex;
            }
        }

        Paint pFont = new Paint();
//        pFont.setTypeface(Typeface.DEFAULT );
        Rect rect = new Rect();

        List<VodInfo.VodSeries> list = vodInfo.seriesMap.get(vodInfo.playFlag);
        int listSize = list.size();
        int w = 1;
        for(int i =0; i < listSize; ++i){
            String name = list.get(i).name;
            pFont.getTextBounds(name, 0, name.length(), rect);
            if(w < rect.width()){
                w = rect.width();
            }
        }
        w += 32;
        int screenWidth = getWindowManager().getDefaultDisplay().getWidth()/3;
        int offset = screenWidth/w;
        if(offset <=2) offset =2;
        if(offset > 6) offset =6;
        mGridViewLayoutMgr.setSpanCount(offset);
        seriesAdapter.setNewData(vodInfo.seriesMap.get(vodInfo.playFlag));

        setSeriesGroupOptions();

        mGridView.postDelayed(new Runnable() {
            @Override
            public void run() {
//                mGridView.smoothScrollToPosition(vodInfo.playIndex);
                customSeriesScrollPos(vodInfo.playIndex);
            }
        }, 100);
    }

    @SuppressLint("NotifyDataSetChanged")
    private void setSeriesGroupOptions(){
        List<VodInfo.VodSeries> list = vodInfo.seriesMap.get(vodInfo.playFlag);
        int listSize = list.size();
        int offset = mGridViewLayoutMgr.getSpanCount();
        seriesGroupOptions.clear();
        GroupCount=(offset==3 || offset==6)?30:20;
        if(listSize>100 && listSize<=400)GroupCount=60;
        if(listSize>400)GroupCount=120;
        if(listSize > 1) {
            tvSeriesGroup.setVisibility(View.VISIBLE);
            int remainedOptionSize = listSize % GroupCount;
            int optionSize = listSize / GroupCount;

            for(int i = 0; i < optionSize; i++) {
                if(vodInfo.reverseSort)
//                    seriesGroupOptions.add(String.format("%d - %d", i * GroupCount + GroupCount, i * GroupCount + 1));
                    seriesGroupOptions.add(String.format("%d - %d", listSize - (i * GroupCount + 1)+1, listSize - (i * GroupCount + GroupCount)+1));
                else
                    seriesGroupOptions.add(String.format("%d - %d", i * GroupCount + 1, i * GroupCount + GroupCount));
            }
            if(remainedOptionSize > 0) {
                if(vodInfo.reverseSort)
//                    seriesGroupOptions.add(String.format("%d - %d", optionSize * GroupCount + remainedOptionSize, optionSize * GroupCount + 1));
                    seriesGroupOptions.add(String.format("%d - %d", listSize - (optionSize * GroupCount + 1)+1, listSize - (optionSize * GroupCount + remainedOptionSize)+1));
                else
                    seriesGroupOptions.add(String.format("%d - %d", optionSize * GroupCount + 1, optionSize * GroupCount + remainedOptionSize));
            }
//            if(vodInfo.reverseSort) Collections.reverse(seriesGroupOptions);

            seriesGroupAdapter.notifyDataSetChanged();
        }else {
            tvSeriesGroup.setVisibility(View.GONE);
        }
    }

    private void setTextShow(TextView view, String tag, String info) {
        if (info == null || info.trim().isEmpty()) {
            view.setVisibility(View.GONE);
            return;
        }
        view.setVisibility(View.VISIBLE);
        view.setText(Html.fromHtml(getHtml(tag, info)));
    }

    private String removeHtmlTag(String info) {
        if (info == null)
            return "";
        return info.replaceAll("\\<.*?\\>", "").replaceAll("\\s", "");
    }

    private void initViewModel() {
        sourceViewModel = new ViewModelProvider(this).get(SourceViewModel.class);
        sourceViewModel.detailResult.observe(this, new Observer<AbsXml>() {
            @Override
            public void onChanged(AbsXml absXml) {
                if (absXml != null && absXml.movie != null && absXml.movie.videoList != null && absXml.movie.videoList.size() > 0) {
                    showSuccess();
                    if(!TextUtils.isEmpty(absXml.msg) && !absXml.msg.equals("数据列表")){
                        Toast.makeText(DetailActivity.this, absXml.msg, Toast.LENGTH_SHORT).show();
                        showEmpty();
                        return;
                    }
                    mVideo = absXml.movie.videoList.get(0);
                    mVideo.id = vodId;
                    if (TextUtils.isEmpty(mVideo.name))mVideo.name = "TVBox";
                    vodInfo = new VodInfo();
                    if((mVideo.pic==null || mVideo.pic.isEmpty()) && !vod_picture.isEmpty()){
                        mVideo.pic=vod_picture;
                    }
                    vodInfo.setVideo(mVideo);
                    vodInfo.sourceKey = mVideo.sourceKey;
                    sourceKey = mVideo.sourceKey;

                    tvName.setText(mVideo.name);
                    setTextShow(tvSite, "来源：", ApiConfig.get().getSource(firstsourceKey).getName());
                    setTextShow(tvYear, "年份：", mVideo.year == 0 ? "" : String.valueOf(mVideo.year));
                    setTextShow(tvArea, "地区：", mVideo.area);
                    setTextShow(tvLang, "语言：", mVideo.lang);
                    if (!firstsourceKey.equals(sourceKey)) {
                    	setTextShow(tvType, "类型：", "[" + ApiConfig.get().getSource(sourceKey).getName() + "] 解析");
                    } else {
                    	setTextShow(tvType, "类型：", mVideo.type);
                    }
                    setTextShow(tvActor, "演员：", mVideo.actor);
                    setTextShow(tvDirector, "导演：", mVideo.director);
                    setTextShow(tvDes, "内容简介：", removeHtmlTag(mVideo.des));
                    if (!TextUtils.isEmpty(mVideo.pic)) {
                        Picasso.get()
                                .load(DefaultConfig.checkReplaceProxy(mVideo.pic))
                                .fit()
                                .centerInside()
                                .placeholder(R.drawable.img_loading_placeholder)
                                .noFade()
                                .error(R.drawable.img_loading_placeholder)
                                .into(ivThumb);
                    } else {
                        ivThumb.setImageResource(R.drawable.img_loading_placeholder);
                    }

                    if (vodInfo.seriesMap != null && vodInfo.seriesMap.size() > 0) {
                        mGridViewFlag.setVisibility(View.VISIBLE);
                        mGridView.setVisibility(View.VISIBLE);
                        tvPlay.setVisibility(View.VISIBLE);
                        mEmptyPlayList.setVisibility(View.GONE);

                        VodInfo vodInfoRecord = RoomDataManger.getVodInfo(sourceKey, vodId);
                        // 读取历史记录
                        if (vodInfoRecord != null) {
                            vodInfo.playIndex = Math.max(vodInfoRecord.playIndex, 0);
                            vodInfo.playFlag = vodInfoRecord.playFlag;
                            vodInfo.playerCfg = vodInfoRecord.playerCfg;
                            vodInfo.reverseSort = vodInfoRecord.reverseSort;
                        } else {
                            vodInfo.playIndex = 0;
                            vodInfo.playFlag = null;
                            vodInfo.playerCfg = "";
                            vodInfo.reverseSort = false;
                        }

                        if (vodInfo.reverseSort) {
                            vodInfo.reverse();
                        }

                        if (vodInfo.playFlag == null || !vodInfo.seriesMap.containsKey(vodInfo.playFlag))
                            vodInfo.playFlag = (String) vodInfo.seriesMap.keySet().toArray()[0];

                        int flagScrollTo = 0;
                        for (int j = 0; j < vodInfo.seriesFlags.size(); j++) {
                            VodInfo.VodSeriesFlag flag = vodInfo.seriesFlags.get(j);
                            if (flag.name.equals(vodInfo.playFlag)) {
                                flagScrollTo = j;
                                flag.selected = true;
                            } else
                                flag.selected = false;
                        }
                        // Keep the displayed file name/address aligned with the restored episode.
                        List<VodInfo.VodSeries> activeSeries = vodInfo.seriesMap.get(vodInfo.playFlag);
                        int activeIndex = Math.max(0, Math.min(vodInfo.playIndex, activeSeries.size() - 1));
                        vodInfo.playIndex = activeIndex;
                        setCurrentPlayingSeries(activeSeries.get(activeIndex));
                        seriesFlagAdapter.setNewData(vodInfo.seriesFlags);
                        mGridViewFlag.scrollToPosition(flagScrollTo);

                        refreshList();
                        if (showPreview) {
                            playCurrentSelection(true, false);
                            llPlayerFragmentContainer.setVisibility(View.VISIBLE);
                            syncPreviewBlockWithPlayerMode();
                            preferPreviewFocus = true;
                            requestPreviewFocus();
                            toggleSubtitleTextSize();
                        }
                        // startQuickSearch();
                    } else {
                        mGridViewFlag.setVisibility(View.GONE);
                        mGridView.setVisibility(View.GONE);
                        tvSeriesGroup.setVisibility(View.GONE);
                        tvPlay.setVisibility(View.GONE);
                        mEmptyPlayList.setVisibility(View.VISIBLE);
                    }
                } else {
                    showEmpty();
                    llPlayerFragmentContainer.setVisibility(View.GONE);
                    llPlayerFragmentContainerBlock.setVisibility(View.GONE);
                    updatePreviewBlockFocusability(false);
                }
            }
        });
        sourceViewModel.playResult.observe(this, new Observer<JSONObject>() {
            @Override
            public void onChanged(JSONObject info) {
                handleFullPlayerResolveResult(info);
            }
        });
    }

    private void updatePreviewBlockFocusability(boolean enabled) {
        if (llPlayerFragmentContainerBlock == null) {
            return;
        }
        llPlayerFragmentContainerBlock.setFocusable(enabled);
        llPlayerFragmentContainerBlock.setFocusableInTouchMode(enabled);
        if (llPlayerFragmentContainer != null) {
            llPlayerFragmentContainer.setFocusable(false);
            llPlayerFragmentContainer.setFocusableInTouchMode(false);
            llPlayerFragmentContainer.setDescendantFocusability(enabled
                    ? ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    : ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        }
        if (!enabled) {
            llPlayerFragmentContainerBlock.clearFocus();
        }
    }

    private void requestPreviewFocus() {
        if (llPlayerFragmentContainerBlock == null || llPlayerFragmentContainerBlock.getVisibility() != View.VISIBLE) {
            return;
        }
        syncPreviewBlockWithPlayerMode();
        if (llPlayerFragmentContainerBlock == null || llPlayerFragmentContainerBlock.getVisibility() != View.VISIBLE) {
            return;
        }
        updatePreviewBlockFocusability(true);
        llPlayerFragmentContainerBlock.post(() -> {
            if (llPlayerFragmentContainerBlock != null
                    && llPlayerFragmentContainerBlock.getVisibility() == View.VISIBLE
                    && !isPreviewFullScreen()) {
                llPlayerFragmentContainerBlock.requestFocus();
                preferPreviewFocus = false;
            }
        });
    }

    private boolean isPreviewFullScreen() {
        return playFragment != null && playFragment.isEmbeddedPlayerFullScreen();
    }

    private void schedulePreviewBlockSync() {
        if (llLayout != null) {
            llLayout.post(new Runnable() {
                @Override
                public void run() {
                    syncPreviewBlockWithPlayerMode();
                }
            });
        } else {
            syncPreviewBlockWithPlayerMode();
        }
    }

    private void syncPreviewBlockWithPlayerMode() {
        if (llPlayerFragmentContainerBlock == null) {
            return;
        }
        boolean playerVisible = showPreview
                && llPlayerFragmentContainer != null
                && llPlayerFragmentContainer.getVisibility() == View.VISIBLE;
        boolean shouldShowBlock = playerVisible && !isPreviewFullScreen();
        llPlayerFragmentContainerBlock.setVisibility(shouldShowBlock ? View.VISIBLE : View.GONE);
        updatePreviewBlockFocusability(shouldShowBlock);
        Log.i(TAG, "previewBlock visible=" + shouldShowBlock
                + " playerVisible=" + playerVisible
                + " full=" + isPreviewFullScreen());
    }

    private String getHtml(String label, String content) {
        if (content == null) {
            content = "";
        }
        return label + "<font color=\"#FFFFFF\">" + content + "</font>";
    }

    private String  vod_picture="";
    private void initData() {
        Intent intent = getIntent();
        if (intent != null && intent.getExtras() != null) {
            Bundle bundle = intent.getExtras();
            vod_picture=bundle.getString("picture", "");
            loadDetail(bundle.getString("id", null), bundle.getString("sourceKey", ""));
        }
    }

    private void loadDetail(String vid, String key) {
        if (vid != null) {
            vodId = vid;
            sourceKey = key;
            firstsourceKey = key;
            showLoading();
            sourceViewModel.getDetail(sourceKey, vodId);
            boolean isVodCollect = RoomDataManger.isVodCollect(sourceKey, vodId);
            if (isVodCollect) {
                tvCollect.setText("取消收藏");
            } else {
                tvCollect.setText("加入收藏");
            }
        }
    }


    private boolean isFirstLoad = true;
    @Subscribe(threadMode = ThreadMode.MAIN)
    public void refresh(RefreshEvent event) {
        if (event.type == RefreshEvent.TYPE_REFRESH) {
            if (event.obj != null) {
                if (event.obj instanceof VodInfo) {
                    syncPlayingVodInfo((VodInfo) event.obj);
                } else if (event.obj instanceof Integer) {
                    int index = (int) event.obj;
                    for (int j = 0; j < Objects.requireNonNull(vodInfo.seriesMap.get(vodInfo.playFlag)).size(); j++) {
                        seriesAdapter.getData().get(j).selected = false;
                        seriesAdapter.notifyItemChanged(j);
                    }
                    seriesAdapter.getData().get(index).selected = true;
                    seriesAdapter.notifyItemChanged(index);
                    if(!isFirstLoad)mGridView.setSelection(index);
                    vodInfo.playIndex = index;
                    setCurrentPlayingSeries(vodInfo.seriesMap.get(vodInfo.playFlag).get(index));
                    //保存历史
                    insertVod(vodInfo.sourceKey, vodInfo);
                    isFirstLoad = false;
                } else if (event.obj instanceof JSONObject) {
                    vodInfo.playerCfg = event.obj.toString();
                    //保存历史
                    insertVod(vodInfo.sourceKey, vodInfo);
                } else if (event.obj instanceof String) {
                    String url = event.obj.toString();
                    //设置更新播放地址
                    setTvPlayUrl(url);
                }

            }
        } else if (event.type == RefreshEvent.TYPE_QUICK_SEARCH_SELECT) {
            if (event.obj != null) {
                Movie.Video video = (Movie.Video) event.obj;
                loadDetail(video.id, video.sourceKey);
            }
        } else if (event.type == RefreshEvent.TYPE_QUICK_SEARCH_WORD_CHANGE) {
            if (event.obj != null) {
                String word = (String) event.obj;
                switchSearchWord(word);
            }
        } else if (event.type == RefreshEvent.TYPE_QUICK_SEARCH_RESULT) {
            try {
                searchData(event.obj == null ? null : (AbsXml) event.obj);
            } catch (Exception e) {
                searchData(null);
            }
        }
    }

    private String searchTitle = "";
    private boolean hadQuickStart = false;
    private final List<Movie.Video> quickSearchData = new ArrayList<>();
    private final List<String> quickSearchWord = new ArrayList<>();
    private ExecutorService searchExecutorService = null;

    private void switchSearchWord(String word) {
        OkGo.getInstance().cancelTag("quick_search");
        quickSearchData.clear();
        searchTitle = word;
        searchResult();
    }

    private void startQuickSearch() {
        initCheckedSourcesForSearch();
        if (hadQuickStart)
            return;
        hadQuickStart = true;
        OkGo.getInstance().cancelTag("quick_search");
        quickSearchWord.clear();
        searchTitle = mVideo.name;
        quickSearchData.clear();
        quickSearchWord.addAll(SearchHelper.splitWords(searchTitle));
        // 分词
//        OkGo.<String>get("http://api.pullword.com/get.php?source=" + URLEncoder.encode(searchTitle) + "&param1=0&param2=0&json=1")
//                .tag("fenci")
//                .execute(new AbsCallback<String>() {
//                    @Override
//                    public String convertResponse(okhttp3.Response response) throws Throwable {
//                        if (response.body() != null) {
//                            return response.body().string();
//                        } else {
//                            throw new IllegalStateException("网络请求错误");
//                        }
//                    }
//
//                    @Override
//                    public void onSuccess(Response<String> response) {
//                        String json = response.body();
//                        try {
//                            for (JsonElement je : new Gson().fromJson(json, JsonArray.class)) {
//                                quickSearchWord.add(je.getAsJsonObject().get("t").getAsString());
//                            }
//                        } catch (Throwable th) {
//                            th.printStackTrace();
//                        }
//                        List<String> words = new ArrayList<>(new HashSet<>(quickSearchWord));
//                        EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_QUICK_SEARCH_WORD, words));
//                    }
//
//                    @Override
//                    public void onError(Response<String> response) {super.onError(response);}
//                });

        searchResult();
    }

    private void searchResult() {
        try {
            if (searchExecutorService != null) {
                searchExecutorService.shutdownNow();
                searchExecutorService = null;
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
        searchExecutorService = Executors.newFixedThreadPool(5);
        List<SourceBean> searchRequestList = new ArrayList<>();
        searchRequestList.addAll(ApiConfig.get().getSourceBeanList());
        SourceBean home = ApiConfig.get().getHomeSourceBean();
        searchRequestList.remove(home);
        searchRequestList.add(0, home);

        ArrayList<String> siteKey = new ArrayList<>();
        for (SourceBean bean : searchRequestList) {
            if (!bean.isSearchable() || !bean.isQuickSearch()) {
                continue;
            }
            if (mCheckSources != null && !mCheckSources.containsKey(bean.getKey())) {
                continue;
            }
            siteKey.add(bean.getKey());
        }
        for (String key : siteKey) {
            searchExecutorService.execute(new Runnable() {
                @Override
                public void run() {
                    sourceViewModel.getQuickSearch(key, searchTitle);
                }
            });
        }
    }

    private void searchData(AbsXml absXml) {
        if (absXml != null && absXml.movie != null && absXml.movie.videoList != null && absXml.movie.videoList.size() > 0) {
            List<Movie.Video> data = new ArrayList<>();
            for (Movie.Video video : absXml.movie.videoList) {
                // 去除当前相同的影片
                if (video.sourceKey.equals(sourceKey) && video.id.equals(vodId))
                    continue;
                data.add(video);
            }
            quickSearchData.addAll(data);
            EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_QUICK_SEARCH, data));
        }
    }

    private void syncPlayingVodInfo(VodInfo playingVodInfo) {
        if (playingVodInfo == null || vodInfo == null || vodInfo.seriesMap == null) {
            return;
        }
        String newFlag = playingVodInfo.playFlag;
        if (TextUtils.isEmpty(newFlag) || !vodInfo.seriesMap.containsKey(newFlag)) {
            return;
        }
        List<VodInfo.VodSeries> newSeriesList = vodInfo.seriesMap.get(newFlag);
        if (newSeriesList == null || newSeriesList.isEmpty()) {
            return;
        }

        VodInfo.VodSeries playingSeries = getPlayingSeries(playingVodInfo, newFlag);
        int newIndex = findSameEpisodeIndex(playingSeries, newSeriesList, playingVodInfo.playIndex);
        vodInfo.playFlag = newFlag;
        vodInfo.playIndex = newIndex;
        if (playingVodInfo.playerCfg != null) {
            vodInfo.playerCfg = playingVodInfo.playerCfg;
        }

        for (VodInfo.VodSeriesFlag flag : vodInfo.seriesFlags) {
            flag.selected = flag.name.equals(newFlag);
        }
        for (List<VodInfo.VodSeries> seriesList : vodInfo.seriesMap.values()) {
            if (seriesList == null) {
                continue;
            }
            for (VodInfo.VodSeries series : seriesList) {
                series.selected = false;
            }
        }
        newSeriesList.get(newIndex).selected = true;

        seriesFlagAdapter.notifyDataSetChanged();
        refreshList();
        setCurrentPlayingSeries(newSeriesList.get(newIndex));

        int flagIndex = -1;
        for (int i = 0; i < vodInfo.seriesFlags.size(); i++) {
            if (vodInfo.seriesFlags.get(i).name.equals(newFlag)) {
                flagIndex = i;
                break;
            }
        }
        if (flagIndex >= 0) {
            mGridViewFlag.scrollToPosition(flagIndex);
            if (mGridViewFlag.hasFocus()) {
                mGridViewFlag.setSelection(flagIndex);
            }
        }
        if (!isFirstLoad && mGridView.hasFocus()) {
            mGridView.setSelection(newIndex);
        }

        insertVod(vodInfo.sourceKey, vodInfo);
        isFirstLoad = false;
    }

    private VodInfo.VodSeries getPlayingSeries(VodInfo playingVodInfo, String flag) {
        if (playingVodInfo == null || playingVodInfo.seriesMap == null || TextUtils.isEmpty(flag)) {
            return null;
        }
        List<VodInfo.VodSeries> playingList = playingVodInfo.seriesMap.get(flag);
        if (playingList == null || playingList.isEmpty()) {
            return null;
        }
        int safeIndex = Math.max(0, Math.min(playingVodInfo.playIndex, playingList.size() - 1));
        return playingList.get(safeIndex);
    }

    private int findSameEpisodeIndex(VodInfo.VodSeries currentSeries, List<VodInfo.VodSeries> targetList, int fallbackIndex) {
        if (targetList == null || targetList.isEmpty()) {
            return 0;
        }
        if (currentSeries != null && !TextUtils.isEmpty(currentSeries.name)) {
            String currentName = normalizeEpisodeName(currentSeries.name);
            for (int i = 0; i < targetList.size(); i++) {
                VodInfo.VodSeries targetSeries = targetList.get(i);
                if (targetSeries != null && currentName.equals(normalizeEpisodeName(targetSeries.name))) {
                    return i;
                }
            }
            int currentEpisode = extractEpisodeNumber(currentSeries.name);
            if (currentEpisode >= 0) {
                for (int i = 0; i < targetList.size(); i++) {
                    VodInfo.VodSeries targetSeries = targetList.get(i);
                    if (targetSeries != null && extractEpisodeNumber(targetSeries.name) == currentEpisode) {
                        return i;
                    }
                }
            }
        }
        return Math.max(0, Math.min(fallbackIndex, targetList.size() - 1));
    }

    private String normalizeEpisodeName(String name) {
        if (name == null) {
            return "";
        }
        return name.toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", "")
                .replaceAll("[\\[\\]【】()（）]", "")
                .replace("第", "")
                .replace("集", "")
                .replace("话", "")
                .replace("期", "");
    }

    private int extractEpisodeNumber(String name) {
        if (name == null) {
            return -1;
        }
        Matcher episodeMatcher = Pattern.compile("(?:第)?(\\d+)(?:集|话|期|$)").matcher(name);
        if (episodeMatcher.find()) {
            try {
                return Integer.parseInt(episodeMatcher.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        Matcher matcher = Pattern.compile("\\d+").matcher(name);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group());
            } catch (NumberFormatException ignored) {
            }
        }
        return -1;
    }

    private void insertVod(String sourceKey, VodInfo vodInfo) {
        if (vodInfo == null) {
            return;
        }
        String recordSourceKey = TextUtils.isEmpty(sourceKey) ? vodInfo.sourceKey : sourceKey;
        if (TextUtils.isEmpty(recordSourceKey)) {
            recordSourceKey = firstsourceKey;
        }
        vodInfo.sourceKey = recordSourceKey;
        try {
            vodInfo.playNote = vodInfo.seriesMap.get(vodInfo.playFlag).get(vodInfo.playIndex).name;
        } catch (Throwable th) {
            vodInfo.playNote = "";
        }
        RoomDataManger.insertVodRecord(recordSourceKey, vodInfo);
        EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_HISTORY_REFRESH));
    }

    @Override
    public void finish() {
        try {
            stopDetailPlayback();
        } finally {
            super.finish();
        }
    }

    private void stopDetailPlayback() {
        if (detailPlaybackStopped) {
            return;
        }
        detailPlaybackStopped = true;
        fullPlayerResolvePending = false;
        fullPlayerResolveSerial++;
        try {
            if (sourceViewModel != null) {
                sourceViewModel.cancelPlayRequests();
            }
            if (playbackResolveExecutor != null) {
                playbackResolveExecutor.shutdownNow();
                playbackResolveExecutor = null;
            }
        } catch (Throwable error) {
            LOG.e("echo-detail-exit cancel failed: " + error.getClass().getSimpleName());
        } finally {
            if (playFragment != null) {
                // Stop before the Activity transition; Fragment destruction can be delayed,
                // and its outstanding parser callback must not restart the hidden old video.
                try {
                    playFragment.stopEmbeddedPlayback();
                } catch (Throwable error) {
                    LOG.e("echo-detail-exit release failed: " + error.getClass().getSimpleName());
                }
            }
        }
    }

    @Override
    protected void onDestroy() {
        stopDetailPlayback();
        super.onDestroy();
        try {
            if (searchExecutorService != null) {
                searchExecutorService.shutdownNow();
                searchExecutorService = null;
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
        OkGo.getInstance().cancelTag("fenci");
        OkGo.getInstance().cancelTag("detail");
        OkGo.getInstance().cancelTag("quick_search");
        EventBus.getDefault().unregister(this);
    }

    @Override
    public void onBackPressed() {
        if (seriesSelect) {
            if (seriesFlagFocus != null && !seriesFlagFocus.isFocused()) {
                seriesFlagFocus.requestFocus();
                return;
            }
        }
        if (showPreview && playFragment != null && playFragment.onBackPressed()) {
            playFragment.syncEmbeddedControllerMode();
            schedulePreviewBlockSync();
            requestPreviewFocus();
            return;
        }
        if(showPreview && playFragment!=null)playFragment.setPlayTitle(false);
        super.onBackPressed();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (showPreview) {
            boolean routedToPreview = false;
            if (event != null && playFragment != null && shouldRouteKeyToPreviewPlayer()) {
                routedToPreview = true;
                Log.i(TAG, "routeKeyToPreview dispatch action=" + event.getAction()
                        + " code=" + event.getKeyCode()
                        + " embeddedFull=" + playFragment.isEmbeddedPlayerFullScreen()
                        + " visuallyFull=" + isPreviewVisuallyFullScreen());
                if (playFragment.dispatchKeyEvent(event)) {
                    schedulePreviewBlockSync();
                    return true;
                }
            }
            if (event != null
                    && playFragment != null
                    && !playFragment.isEmbeddedPlayerFullScreen()
                    && llPlayerFragmentContainerBlock != null
                    && llPlayerFragmentContainerBlock.getVisibility() == View.VISIBLE) {
                int keyCode = event.getKeyCode();
                boolean confirm = keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                        || keyCode == KeyEvent.KEYCODE_ENTER
                        || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
                if (event.getAction() == KeyEvent.ACTION_DOWN && confirm) {
                    if (llPlayerFragmentContainerBlock.hasFocus()) {
                        openFullPlayer();
                        return true;
                    }
                }
                if (isPreviewNavigationKey(keyCode) && isFocusInsidePreview()) {
                    return false;
                }
            }
            if (event != null
                    && playFragment != null
                    && playFragment.isEmbeddedPlayerFullScreen()
                    && !routedToPreview
                    && playFragment.dispatchKeyEvent(event)) {
                schedulePreviewBlockSync();
                return true;
            }
            return super.dispatchKeyEvent(event);
        }
        if (event != null && playFragment != null && playFragment.dispatchKeyEvent(event)) {
            schedulePreviewBlockSync();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private boolean isPreviewNavigationKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_UP
                || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                || keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
    }

    private boolean shouldRouteKeyToPreviewPlayer() {
        return playFragment != null
                && (playFragment.isEmbeddedPlayerFullScreen()
                || isPreviewVisuallyFullScreen()
                || isFocusInsidePreview());
    }

    private boolean isPreviewVisuallyFullScreen() {
        if (llPlayerFragmentContainer == null || llPlayerFragmentContainer.getVisibility() != View.VISIBLE) {
            return false;
        }
        int width = llPlayerFragmentContainer.getWidth();
        int height = llPlayerFragmentContainer.getHeight();
        if (width <= 0 || height <= 0) {
            return false;
        }
        View decor = getWindow() == null ? null : getWindow().getDecorView();
        int screenWidth = decor == null ? getResources().getDisplayMetrics().widthPixels : decor.getWidth();
        int screenHeight = decor == null ? getResources().getDisplayMetrics().heightPixels : decor.getHeight();
        return width >= screenWidth * 0.90f && height >= screenHeight * 0.85f;
    }

    private boolean isFocusInsidePreview() {
        View focused = getCurrentFocus();
        if (focused == null || llPlayerFragmentContainer == null) {
            return false;
        }
        if (focused == llPlayerFragmentContainerBlock) {
            return true;
        }
        View current = focused;
        while (current != null) {
            if (current == llPlayerFragmentContainer) {
                return true;
            }
            android.view.ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return false;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (showPreview) {
            if (event != null && playFragment != null && shouldRouteKeyToPreviewPlayer() && playFragment.onKeyDown(keyCode, event)) {
                schedulePreviewBlockSync();
                return true;
            }
            return super.onKeyDown(keyCode, event);
        }
        if (event != null && playFragment != null && playFragment.onKeyDown(keyCode,event)) {
            schedulePreviewBlockSync();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (showPreview) {
            if (event != null && playFragment != null && shouldRouteKeyToPreviewPlayer() && playFragment.onKeyUp(keyCode, event)) {
                schedulePreviewBlockSync();
                return true;
            }
            return super.onKeyUp(keyCode, event);
        }
        if (event != null && playFragment != null && playFragment.onKeyUp(keyCode,event)) {
            schedulePreviewBlockSync();
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    // preview
    VodInfo previewVodInfo = null;
    void toggleSubtitleTextSize() {
        int subtitleTextSize  = SubtitleHelper.getTextSize(this);
        subtitleTextSize *= 0.6;
        EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_SUBTITLE_SIZE_CHANGE, subtitleTextSize));
    }

    private void setTvPlayUrl(String url)
    {
        currentPlayUrl = url == null ? "" : url;
        setTextShow(tvPlayUrl, "播放地址：", currentPlayUrl);
        // Keep a long parser URL from becoming a multi-line block that pushes
        // the action row out of the detail panel on touch/small-screen builds.
        tvPlayUrl.setSingleLine(true);
        tvPlayUrl.setMaxLines(1);
        tvPlayUrl.setEllipsize(TextUtils.TruncateAt.END);
    }

    private void setCurrentPlayingSeries(VodInfo.VodSeries series) {
        if (series == null) {
            tvPlayingFileName.setVisibility(View.GONE);
            return;
        }
        setTextShow(tvPlayingFileName, "正在播放：", series.name);
        if (tvPlayingFileName.getVisibility() == View.VISIBLE) {
            tvPlayingFileName.setSelected(true);
        }
        setTvPlayUrl(series.url);
    }

    private void bindButtonFocus(TextView view) {
        if (view == null) {
            return;
        }
        view.setOnFocusChangeListener((v, hasFocus) -> {
            ViewCompat.setElevation(v, hasFocus ? 10f : 0f);
            v.setScaleX(hasFocus ? 1.006f : 1f);
            v.setScaleY(hasFocus ? 1.006f : 1f);
            v.setTranslationY(hasFocus ? -1f : 0f);
        });
    }
}
