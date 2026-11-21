package com.captiva.musicplayer;

import android.content.Context;
import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import java.util.List;

/**
 * 播放列表弹窗(当前播放队列)的列表适配器。
 *
 * <p>数据是**队列快照**(MainActivity 传入前已 new ArrayList 拷贝):弹窗打开期间
 * MusicService 的 playList 可能被自动切歌/缓存回填改动,直接持有活引用会在
 * 滚动时踩到并发修改。真正需要同步的只有"现在是第几首在播",用
 * {@link #setPlayingIndex(int)} 单独刷新即可。
 *
 * <p>性能:车机列表动辄 800+ 首,虽然是 ListView 复用,仍然做了三件事 ——
 * <ul>
 *   <li>ViewHolder 复用,getView 里不 findViewById;</li>
 *   <li>颜色在构造时一次性取好(不在 bind 里 ContextCompat.getColor);</li>
 *   <li>背景只按"是否当前行"换资源,不新建 Drawable 实例。</li>
 * </ul>
 * 注意背景不能缓存同一个 Drawable 实例跨行共享:bg_settings_item 是
 * selector(带 state_pressed),Drawable 的 callback/state 是单例的,
 * 多行共用一个实例会出现"按一行、全列表一起变色"。
 */
public class PlaylistAdapter extends BaseAdapter {

    /** 当前播放行左侧显示的字形(代替序号,一眼看出播到哪) */
    private static final String MARK_PLAYING = "▶";

    private final List<MusicBean> data;
    private final LayoutInflater inflater;

    private final int colorTextPrimary;
    private final int colorTextSecondary;
    private final int colorPlayingText;
    private final int colorPlayingTextSub;

    /** 当前播放行在**队列**里的下标,-1 = 没有正在播放的(队列还没起播) */
    private int playingIndex;

    public PlaylistAdapter(Context context, List<MusicBean> data, int playingIndex) {
        this.data = data;
        this.playingIndex = playingIndex;
        this.inflater = LayoutInflater.from(context);
        this.colorTextPrimary = ContextCompat.getColor(context, R.color.text_primary);
        this.colorTextSecondary = ContextCompat.getColor(context, R.color.text_secondary);
        this.colorPlayingText = ContextCompat.getColor(context, R.color.playing_text);
        this.colorPlayingTextSub = ContextCompat.getColor(context, R.color.playing_text_sub);
    }

    /**
     * 更新高亮行(自动切歌时由 MainActivity 的状态广播调用)。
     * 下标没变就不刷,避免每 300ms 的进度广播把整屏重绑一遍。
     */
    public void setPlayingIndex(int index) {
        if (index == playingIndex) {
            return;
        }
        playingIndex = index;
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return data.size();
    }

    @Override
    public MusicBean getItem(int position) {
        return data.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        Holder h;
        if (convertView == null) {
            convertView = inflater.inflate(R.layout.dialog_playlist_item, parent, false);
            h = new Holder();
            h.index = (TextView) convertView.findViewById(R.id.tv_pl_index);
            h.title = (TextView) convertView.findViewById(R.id.tv_pl_title);
            h.artist = (TextView) convertView.findViewById(R.id.tv_pl_artist);
            h.dur = (TextView) convertView.findViewById(R.id.tv_pl_dur);
            convertView.setTag(h);
        } else {
            h = (Holder) convertView.getTag();
        }

        MusicBean bean = data.get(position);
        boolean playing = (position == playingIndex);

        if (playing) {
            convertView.setBackgroundResource(R.drawable.bg_playlist_item_playing);
            h.index.setText(MARK_PLAYING);
            h.index.setTextColor(colorPlayingTextSub);
            h.title.setTextColor(colorPlayingText);
            h.artist.setTextColor(colorPlayingTextSub);
            h.dur.setTextColor(colorPlayingTextSub);
            h.title.setTypeface(null, Typeface.BOLD);
        } else {
            convertView.setBackgroundResource(R.drawable.bg_settings_item);
            h.index.setText(String.valueOf(position + 1));
            h.index.setTextColor(colorTextSecondary);
            h.title.setTextColor(colorTextPrimary);
            h.artist.setTextColor(colorTextSecondary);
            h.dur.setTextColor(colorTextSecondary);
            h.title.setTypeface(null, Typeface.NORMAL);
        }

        h.title.setText(bean.getTitle());
        h.artist.setText(bean.getArtist());
        h.dur.setText(MusicBean.formatDuration(bean.getDuration()));
        return convertView;
    }

    /** getView 复用容器(不 static —— 内部类实例只在适配器存活期内存在) */
    private static final class Holder {
        TextView index;
        TextView title;
        TextView artist;
        TextView dur;
    }
}
