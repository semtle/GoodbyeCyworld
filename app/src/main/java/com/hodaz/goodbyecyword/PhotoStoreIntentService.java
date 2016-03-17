package com.hodaz.goodbyecyword;

import android.app.IntentService;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.support.v7.app.NotificationCompat;

import com.hodaz.goodbyecyword.common.Defines;
import com.hodaz.goodbyecyword.common.Utils;
import com.hodaz.goodbyecyword.model.Folder;
import com.hodaz.goodbyecyword.model.Post;
import com.nostra13.universalimageloader.cache.disc.naming.HashCodeFileNameGenerator;
import com.nostra13.universalimageloader.cache.memory.impl.WeakMemoryCache;
import com.nostra13.universalimageloader.core.DisplayImageOptions;
import com.nostra13.universalimageloader.core.ImageLoader;
import com.nostra13.universalimageloader.core.ImageLoaderConfiguration;
import com.nostra13.universalimageloader.core.display.RoundedBitmapDisplayer;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * An {@link IntentService} subclass for handling asynchronous task requests in
 * a service on a separate handler thread.
 * <p/>
 * TODO: Customize class - update intent actions, extra parameters and static
 * helper methods.
 */
public class PhotoStoreIntentService extends IntentService {

    private static final String TAG = "PhotoStoreIntentService";
    private ArrayList<Post> postList;
    private static final int MAX_RETRY_COUNT = 100;
    private NotificationCompat.Builder builder;
    private ImageLoader imageLoader;

    public PhotoStoreIntentService() {
        super("PhotoStoreIntentService");
    }

    @Override
    public void onCreate() {
        super.onCreate();
        initImageLoader();
    }

    private void initImageLoader(){
        imageLoader = ImageLoader.getInstance();
        DisplayImageOptions defaultOptions = new DisplayImageOptions.Builder()
                .cacheOnDisk(true).cacheInMemory(true)
                .build();

        ImageLoaderConfiguration config = new ImageLoaderConfiguration.Builder(PhotoStoreIntentService.this)
                .defaultDisplayImageOptions(defaultOptions)
                .memoryCache(new WeakMemoryCache())
                .build();
        imageLoader.init(config);
    }



    @Override
    protected void onHandleIntent(Intent intent) {
        if (intent != null) {
            final String action = intent.getAction();

            String cyID = intent.getStringExtra("CyID");
            Folder folder = intent.getParcelableExtra("Folder");
            if (folder != null ) {
                String folderID = folder.id;
                String folderTitle = folder.title;

                showNoti(folderTitle);
                String url = String.format(Defines.URL_GET_CONTENT_LIST, cyID, folderID);
                CommonLog.i(TAG, "url : " + url);
                try {
                    Document doc = Jsoup.connect(url).timeout(30000).cookies(Utils.getCookie()).get();
                    Elements posts = doc.getElementsByClass("post");
                    Elements photos = doc.getElementsByClass("postImage");

                    CommonLog.e(TAG, "onHandleIntent : " + doc.toString());

                    //TODO : max integer size 보다가 게시물이 많으면 에러가 날것 같군요
                    postList = new ArrayList<Post>(posts.size());

                    for (Element e : posts) {
                        String postId = e.attr("id");
                        String imgUrl = e.select("figure").attr("style");
                        imgUrl = imgUrl.replace("background-image:url('", "");
                        imgUrl = imgUrl.replace("');", "");
                        imgUrl = imgUrl.replace("cythumb.cyworld.com/269x269/", "");
                        imgUrl = imgUrl.replace("file_down", "vm_file_down");

                        CommonLog.e(TAG, postId + "\n" + imgUrl);

                        Post post = new Post();
                        post.folderID = folderID;
                        post.folderTitle = folderTitle;
                        post.postID = postId;
                        post.postImg = imgUrl;

                        CommonLog.w(TAG, "postImg : " +  post.postImg);
                        CommonLog.w(TAG, "postID" + post.postID);
                        postList.add(post);
                    }

                    //savePostImages(folderTitle);

                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private void savePostImages(String folderTitle){

        builder.setProgress(postList.size(), 0, false);

        if(postList != null && postList.size() > 0){
            File storageDir = null;
            // 저장될 디렉토리를 구한다.
            storageDir = new File(String.format(Defines.EXTRA_PICTURE_OUTPUT_URI, folderTitle));
            // 디렉토리 없으면 생성
            if (!storageDir.exists()) {
                storageDir.mkdirs();
            }

            int countFailure = 0;
            int countProgess = 0;

            for(Post post : postList){
                Bitmap bitmap = imageLoader.loadImageSync(post.postImg);

                File saveFile = null;
                try {
                    saveFile = createImageFile(post.postID, storageDir);
                } catch (IOException e) {
                }

                if (saveFile == null) {
                    countFailure++;
                    continue;
                }

                FileOutputStream os = null;
                try {
                    os = new FileOutputStream( saveFile );
                    bitmap.compress( Bitmap.CompressFormat.JPEG, 88, os );

                } catch( FileNotFoundException e ) {
                    countFailure++;
                    //e.printStackTrace();
                    continue;
                } catch( Exception e ) {
                    countFailure++;
                    //e.printStackTrace();
                    continue;
                } finally {
                    try {
                        if (os != null ) {
                            os.close();
                            countProgess++;
                            builder.setProgress(postList.size(), countProgess, false);
                        }
                    } catch( Exception ex ) {
                    }
                }

                galleryAddPic( saveFile );
            }
        }
    }

    private File createImageFile(String postTitle, File storageDir) throws IOException {

        File imageFile = null;
        /*final String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        final String baseFileName = IMAGE_FILE_PREFIX + "_" + timeStamp;*/
        int retry = 0;

        while (retry < MAX_RETRY_COUNT) {
            String fileName = postTitle;
            if (retry > 0) {
                // 파일명이 중복되어 있으면 (xx)를 추가하여 재시도
                fileName = fileName + " (" + retry+")";
            }
            fileName = fileName + ".jpg";

            CommonLog.e(TAG, "createImageFile : " + postTitle);

            File file = new File(storageDir, fileName);
            if (!file.exists()) {
                imageFile = file;
                break;
            }

            retry++;
        }

        return imageFile;
    }

    /** 저장된 이미지 파일을 MediaStore에 추가 */
    private void galleryAddPic(File saveFile) {

        if (saveFile != null) {
            Intent mediaScanIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
            Uri contentUri = Uri.fromFile(saveFile);
            mediaScanIntent.setData(contentUri);
            sendBroadcast(mediaScanIntent);
        }
    }

    private void showNoti(String folderTitle) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT);

        builder = new NotificationCompat.Builder(this);
        builder.setSmallIcon(R.mipmap.ic_launcher);
        builder.setTicker("NotificationCompat.Builder");
        builder.setWhen(System.currentTimeMillis());
        // mCompatBuilder.setNumber(10);
        builder.setContentTitle("당신의 추억을 폰으로 저장 중입니다.");
        builder.setContentText("현재 저장 중인 폴더는 '" + folderTitle + "' 입니다.");
        builder.setDefaults(Notification.DEFAULT_SOUND | Notification.DEFAULT_VIBRATE);
        builder.setContentIntent(pendingIntent);
        builder.setAutoCancel(false);
        // mCompatBuilder.setOngoing(true);

        nm.notify(222, builder.build());
    }
}