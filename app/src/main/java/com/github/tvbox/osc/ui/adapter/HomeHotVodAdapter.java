package com.github.tvbox.osc.ui.adapter;

import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.util.DefaultConfig;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.ImgUtil;
import com.orhanobut.hawk.Hawk;
import com.squareup.picasso.Picasso;

import java.util.ArrayList;

import me.jessyan.autosize.utils.AutoSizeUtils;

public class HomeHotVodAdapter extends BaseQuickAdapter<Movie.Video, BaseViewHolder> {
    // The footer contains an optional note plus a two-line title.  Keep the
    // poster tall enough for that content when a source supplies a wide ratio.
    private static final int MIN_POSTER_HEIGHT_MM = 120;
    private int defaultWidth;
    private final ImgUtil.Style style;
    private String  tvRateValue;

    /**
     * style 数据结构：ratio 指定宽高比（宽 / 高），type 表示风格（例如 rect、list）
     */
    public HomeHotVodAdapter(ImgUtil.Style style,String tvRate) {
        super(R.layout.item_user_hot_vod, new ArrayList<>());
        if(style!=null){
            this.defaultWidth=ImgUtil.getStyleDefaultWidth(style);
        }
        this.style=style;
        this.tvRateValue=tvRate;
    }

    @Override
    protected void convert(BaseViewHolder helper, Movie.Video item) {
        FrameLayout tvDel = helper.getView(R.id.delFrameLayout);
        if (HawkConfig.hotVodDelete) {
            tvDel.setVisibility(View.VISIBLE);
        } else {
            tvDel.setVisibility(View.GONE);
        }

        TextView tvRate = helper.getView(R.id.tvRate);
        if (Hawk.get(HawkConfig.HOME_REC, 0) == 2){
            SourceBean bean =  ApiConfig.get().getSource(item.sourceKey);
            if(bean!=null){
                tvRateValue=bean.getName();
            }else {
                tvRateValue="搜";
            }
        }
        tvRate.setText(tvRateValue);

        TextView tvNote = helper.getView(R.id.tvNote);
        if (item.note == null || item.note.isEmpty()) {
            tvNote.setVisibility(View.GONE);
        } else {
            tvNote.setText(item.note);
            tvNote.setVisibility(View.VISIBLE);
        }
        helper.setText(R.id.tvName, item.name);
        ImageView ivThumb = helper.getView(R.id.ivThumb);

        int newWidth = ImgUtil.defaultWidth;
        int newHeight = ImgUtil.defaultHeight;
        if(style!=null){
            newWidth = defaultWidth;
            newHeight = (int)(newWidth / style.ratio);
        }

        //由于部分电视机使用glide报错
        if (!TextUtils.isEmpty(item.pic)) {
            item.pic=item.pic.trim();
            if(ImgUtil.isBase64Image(item.pic)){
                // 如果是 Base64 图片，解码并设置
                ivThumb.setImageBitmap(ImgUtil.decodeBase64ToBitmap(item.pic));
            }else {
                    Picasso.get()
                        .load(DefaultConfig.checkReplaceProxy(item.pic))
                        .fit()
                        .centerInside()
                        .placeholder(R.drawable.img_loading_placeholder)
                        .noFade()
                        .error(ImgUtil.createTextDrawable(item.name))
                        .into(ivThumb);
            }
        } else {
            ivThumb.setImageDrawable(ImgUtil.createTextDrawable(item.name));
        }
        applyStyleToImage(ivThumb);//动态设置宽高
        ensurePosterFooterFits(ivThumb);
    }
    /**
     * 根据传入的 style 动态设置 ImageView 的高度：高度 = 宽度 / ratio
     */
    private void applyStyleToImage(final ImageView ivThumb) {
        if(style!=null){
            ViewGroup container = (ViewGroup) ivThumb.getParent();
            int width = defaultWidth;
            int height = (int) (width / style.ratio);
            int footerMinHeight = AutoSizeUtils.mm2px(mContext, MIN_POSTER_HEIGHT_MM);
            ViewGroup.LayoutParams containerParams = container.getLayoutParams();
            containerParams.height = Math.max(AutoSizeUtils.mm2px(mContext, height), footerMinHeight); // 高度
            containerParams.width = AutoSizeUtils.mm2px(mContext, width); // 宽度
            container.setLayoutParams(containerParams);
            container.requestLayout();
        }
    }

    /**
     * The poster frame is clipped to its rounded outline.  A fixed poster height
     * can therefore cut the lower half of a two-line title on phones or on a
     * source with a wide poster ratio. Measure the footer against the actual
     * runtime width and expand only when its content needs more room.
     */
    private void ensurePosterFooterFits(final ImageView ivThumb) {
        final ViewGroup container = (ViewGroup) ivThumb.getParent();
        final View footer = container.findViewById(R.id.posterFooter);
        if (footer == null) {
            return;
        }
        footer.post(new Runnable() {
            @Override
            public void run() {
                int width = container.getMeasuredWidth();
                if (width <= 0) {
                    return;
                }
                footer.measure(
                        View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                int footerHeight = footer.getMeasuredHeight();
                int breathingRoom = AutoSizeUtils.mm2px(mContext, 8);
                int requiredHeight = footerHeight + breathingRoom;
                ViewGroup.LayoutParams params = container.getLayoutParams();
                int currentHeight = Math.max(container.getMeasuredHeight(), params.height);
                if (requiredHeight > currentHeight) {
                    params.height = requiredHeight;
                    container.setLayoutParams(params);
                    container.requestLayout();
                }
            }
        });
    }
}
