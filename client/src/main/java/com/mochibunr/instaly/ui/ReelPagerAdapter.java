package com.mochibunr.instaly.ui;
import android.view.*; import androidx.annotation.*; import androidx.media3.common.MediaItem; import androidx.media3.exoplayer.ExoPlayer; import androidx.recyclerview.widget.RecyclerView; import com.mochibunr.instaly.R; import com.mochibunr.instaly.model.Reel; import java.util.*;
public final class ReelPagerAdapter extends RecyclerView.Adapter<ReelPagerAdapter.Holder>{
 private final List<Reel> reels; public ReelPagerAdapter(List<Reel> reels){this.reels=reels;}
 @NonNull public Holder onCreateViewHolder(@NonNull ViewGroup p,int t){return new Holder(p);}
 public void onBindViewHolder(@NonNull Holder h,int pos){h.bind(reels.get(pos));}
 public void onViewRecycled(@NonNull Holder h){h.release();super.onViewRecycled(h);} public int getItemCount(){return reels.size();}
 public static final class Holder extends RecyclerView.ViewHolder{
  private final androidx.media3.ui.PlayerView pv; private final android.widget.TextView a,c,s; private ExoPlayer player;
  Holder(ViewGroup p){super(LayoutInflater.from(p.getContext()).inflate(R.layout.item_reel,p,false));pv=itemView.findViewById(R.id.player);a=itemView.findViewById(R.id.author);c=itemView.findViewById(R.id.caption);s=itemView.findViewById(R.id.audio);}
  void bind(Reel r){release();a.setText("@"+r.username);c.setText(r.caption);s.setText("♫  "+r.audio);player=new ExoPlayer.Builder(itemView.getContext()).build();pv.setPlayer(player);player.setMediaItem(MediaItem.fromUri(r.videoUrl));player.setRepeatMode(ExoPlayer.REPEAT_MODE_ONE);player.prepare();}
  void setPlaying(boolean v){if(player!=null)player.setPlayWhenReady(v);} void release(){if(player!=null){player.release();player=null;}pv.setPlayer(null);}
 }}
