package com.mochibunr.instaly.data;
import com.mochibunr.instaly.model.Reel; import java.util.*;
public final class DemoReelRepository implements ReelRepository {
 public List<Reel> loadInitial(){return Arrays.asList(
  new Reel("1","instaly","A real vertical player, ready for the Instagram data adapter.","Original audio","https://storage.googleapis.com/gtv-videos-bucket/sample/ForBiggerEscapes.mp4"),
  new Reel("2","instaly.lab","Swipe vertically. Media3 handles playback while the feed stays repository-driven.","Original audio","https://storage.googleapis.com/gtv-videos-bucket/sample/ForBiggerFun.mp4"),
  new Reel("3","instaly","The next step is plugging the authenticated Instagram provider into this same interface.","Original audio","https://storage.googleapis.com/gtv-videos-bucket/sample/ForBiggerJoyrides.mp4"));}}
