package com.mochibunr.instaly.data;
import com.mochibunr.instaly.model.Reel; import java.util.*;
/** Boundary for an unofficial/private Instagram client adapter. */
public final class InstagramPrivateRepository implements ReelRepository {
 public List<Reel> loadInitial(){return Collections.emptyList();}
}