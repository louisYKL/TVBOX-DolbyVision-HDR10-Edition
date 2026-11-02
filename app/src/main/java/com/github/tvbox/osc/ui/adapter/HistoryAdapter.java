package com.github.tvbox.osc.ui.adapter;

import android.text.TextUtils;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.util.DefaultConfig;
import com.github.tvbox.osc.util.ImgUtil;
import com.squareup.picasso.Picasso;

import java.util.ArrayList;

import me.jessyan.autosize.utils.AutoSizeUtils;

/**
 * @author pj567
 * @date :2020/12/21
 * @description:
 */
public class HistoryAdapter extends BaseQuickAdapter<VodInfo, BaseViewHolder> {
    public HistoryAdapter() {
        super(R.layout.item_grid, new ArrayList<>());
    }

    @Override
    protected void convert(BaseViewHolder helper, VodInfo item) {
        FrameLayout tvDel = helper.getView(R.id.delFrameLayout);
        if (com.github.tvbox.osc.util.HawkConfig.hotVodDelete) {
            tvDel.setVisibility(View.VISIBLE);
        } else {
            tvDel.setVisibility(View.GONE);
        }
    
        TextView tvYear = helper.getView(R.id.tvYear);
        SourceBean bean =  ApiConfig.get().getSource(item.sourceKey);
        if(bean!=null){
            tvYear.setText(bean.getName());
        }else {
            tvYear.setText("搜");
//            tvYear.setVisibility(View.GONE);
        }
        helper.setVisible(R.id.tvLang, false);
        helper.setVisible(R.id.tvArea, false);
        if (item.note == null || item.note.isEmpty()) {
            helper.setVisible(R.id.tvNote, false);
        } else {
            helper.setText(R.id.tvNote, item.note);
        }
        helper.setText(R.id.tvName, item.name);
        // helper.setText(R.id.tvActor, item.actor);
        ImageView ivThumb = helper.getView(R.id.ivThumb);
        //由于部分电视机使用glide报错
        if (!TextUtils.isEmpty(item.pic)) {
            Picasso.get()
                    .load(DefaultConfig.checkReplaceProxy(item.pic))
                    .fit()
                    .centerInside()
                    .placeholder(R.drawable.img_loading_placeholder)
                    .noFade()
                    .error(ImgUtil.createTextDrawable(item.name))
                    .into(ivThumb);
        } else {
            ivThumb.setImageDrawable(ImgUtil.createTextDrawable(item.name));
        }
    }
}
