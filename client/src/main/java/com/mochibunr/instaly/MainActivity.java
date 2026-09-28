package com.mochibunr.instaly;
import android.content.*; import android.os.*; import android.view.*; import android.widget.*; import androidx.appcompat.app.*; import androidx.recyclerview.widget.RecyclerView; import androidx.viewpager2.widget.ViewPager2; import com.google.android.material.dialog.MaterialAlertDialogBuilder; import com.mochibunr.instaly.auth.*; import com.mochibunr.instaly.data.*; import com.mochibunr.instaly.ui.*;
public final class MainActivity extends AppCompatActivity{
 private ViewPager2 pager; private ReelPagerAdapter adapter; private InstagramAuthProviderImpl auth;
 @Override protected void onCreate(Bundle b){super.onCreate(b);auth=new InstagramAuthProviderImpl(this);showLogin();}
 private void showLogin(){setContentView(R.layout.screen_login);
  findViewById(R.id.connectInstagram).setOnClickListener(v->new MaterialAlertDialogBuilder(this).setTitle("Instagram connection")
   .setMessage("The client is ready for an Instagram auth provider, but this build does not collect Instagram passwords. Configure the provider/backend before using a real account.")
   .setPositiveButton("Open Instagram",(d,w)->startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://www.instagram.com/accounts/login/")))).setNegativeButton("Cancel",null).show());
  findViewById(R.id.demoFeed).setOnClickListener(v->{auth.markDemoConnected();showReels(new DemoReelRepository());});}
 private void showReels(ReelRepository repo){setContentView(R.layout.screen_reels);pager=findViewById(R.id.reelPager);adapter=new ReelPagerAdapter(repo.loadInitial());pager.setAdapter(adapter);
  pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback(){@Override public void onPageSelected(int p){for(int i=0;i<adapter.getItemCount();i++)setPlaying(i,i==p);}});
  pager.post(()->setPlaying(0,true)); findViewById(R.id.navHome).setOnClickListener(v->toast("Home is next."));
  findViewById(R.id.navSearch).setOnClickListener(v->toast("Search is next.")); findViewById(R.id.navCreate).setOnClickListener(v->toast("Create is next."));
  findViewById(R.id.navProfile).setOnClickListener(v->toast("Profile is next."));}
 private void setPlaying(int pos,boolean play){View child=pager.getChildAt(0);if(!(child instanceof RecyclerView))return;RecyclerView.ViewHolder h=((RecyclerView)child).findViewHolderForAdapterPosition(pos);if(h instanceof ReelPagerAdapter.Holder)((ReelPagerAdapter.Holder)h).setPlaying(play);}
 private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_SHORT).show();}
}
