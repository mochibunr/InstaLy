package com.mochibunr.instaly.data;
import com.mochibunr.instaly.model.Reel; import java.util.List;
public interface ReelRepository { List<Reel> loadInitial(); }