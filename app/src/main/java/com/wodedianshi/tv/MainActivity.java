  private void addDialog(){LinearLayout f=new LinearLayout(this);f.setOrientation(1);EditText n=new EditText(this);n.setHint("频道名称");EditText u=new EditText(this);u.setHint("播放网址（http/https，m3u8 等）");f.addView(n);f.addView(u);new AlertDialog.Builder(this).setTitle("添加自建频道").setView(f).setNegativeButton("取消",null).setPositiveButton("保存",(d,w)->{String name=n.getText().toString().trim(),url=u.getText().toString().trim();if(!name.isEmpty()&&(url.startsWith("http://")||url.startsWith("https://"))){Channel c=new Channel(name,url,"自建频道");channels.add(c);rebuildChannelIndexes();persist();activeCategory="自建频道";favoriteMode=false;refresh();play(channels.indexOf(c));}else toast("请填写频道名称和有效网址");}).show();}
  private void showFullscreenChannelMenu(){
    if(!fullScreenPlayback||fullscreenMenuPopup!=null&&fullscreenMenuPopup.isShowing())return;
    if(fullscreenAspectMenuItem==null){fullscreenAspectMenuItem=new Button(this);fullscreenAspectMenuItem.setText("画面比例");fullscreenAspectMenuItem.setAllCaps(false);fullscreenAspectMenuItem.setFocusable(true);fullscreenAspectMenuItem.setFocusableInTouchMode(true);styleTvButton(fullscreenAspectMenuItem,Color.rgb(35,55,78));fullscreenAspectMenuItem.setOnClickListener(v->{closeFullscreenChannelMenu();showAspectRatioDialog(video,"live_aspect_mode");});}
    if(fullscreenAspectMenuItem.getParent()==null&&categoryPane!=null)categoryPane.addView(fullscreenAspectMenuItem,1,new LinearLayout.LayoutParams(-1,dp(48)));
    if(categoryPane.getParent()==rootLayout)rootLayout.removeView(categoryPane);
    if(channelPane.getParent()==rootLayout)rootLayout.removeView(channelPane);
    categoryPane.setVisibility(View.VISIBLE);channelPane.setVisibility(View.VISIBLE);
    LinearLayout menu=new LinearLayout(this);menu.setOrientation(0);menu.setBackgroundColor(Color.rgb(8,13,24));
    menu.addView(categoryPane,new LinearLayout.LayoutParams(dp(104),-1));
    menu.addView(channelPane,new LinearLayout.LayoutParams(dp(250),-1));
    // categoryPane/channelPane are detached from the Activity and re-parented into
    // a PopupWindow at runtime. Recheck their complete descendant tree at this
    // boundary: the startup layout guard cannot see children added after startup.
    normalizeFrameLayoutChildren(menu);
    int screenWidth=getResources().getDisplayMetrics().widthPixels;
    int menuWidth=Math.min(screenWidth,dp(354));
    fullscreenMenuPopup=new PopupWindow(menu,menuWidth,-1,true);
    // 弹出层只占左侧菜单宽度，背景透明；播放器仍在其后持续显示。
    fullscreenMenuPopup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
    fullscreenMenuPopup.setOutsideTouchable(true);
    fullscreenMenuPopup.setOnDismissListener(()->{
      if(categoryPane.getParent() instanceof android.view.ViewGroup)((android.view.ViewGroup)categoryPane.getParent()).removeView(categoryPane);
      if(channelPane.getParent() instanceof android.view.ViewGroup)((android.view.ViewGroup)channelPane.getParent()).removeView(channelPane);
      rootLayout.addView(categoryPane,0,new LinearLayout.LayoutParams(dp(104),-1));
      rootLayout.addView(channelPane,1,new LinearLayout.LayoutParams(dp(250),-1));
      // Reparenting can expose a different measurement path; validate the restored
      // tree before the next frame is measured.
      normalizeFrameLayoutChildren(rootLayout);
      categoryPane.setVisibility(View.GONE);channelPane.setVisibility(View.GONE);
      fullscreenMenuPopup=null;
      if(fullscreenAspectMenuItem!=null&&fullscreenAspectMenuItem.getParent() instanceof android.view.ViewGroup)((android.view.ViewGroup)fullscreenAspectMenuItem.getParent()).removeView(fullscreenAspectMenuItem);
      if(fullScreenPlayback&&isTouchDevice())showFullscreenControls();else if(fullscreenMenuButton!=null)fullscreenMenuButton.setVisibility(View.GONE);
      if(video!=null&&!isTouchDevice())video.requestFocus();
    });
    fullscreenMenuPopup.showAtLocation(rootLayout,Gravity.LEFT|Gravity.TOP,0,0);
    fullscreenMenuButton.setVisibility(View.GONE);
    // 弹出层挂载后，直接滚动到当前正在播放的频道，并把遥控器焦点放在该行。
    focusSelectedChannelInMenu();
  }
  private boolean isTouchDevice(){
    return getPackageManager().hasSystemFeature(android.content.pm.PackageManager.FEATURE_TOUCHSCREEN);
  }