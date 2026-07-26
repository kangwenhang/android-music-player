package com.captiva.musicplayer;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * 文件夹分类界面
 * 展示所有音乐文件夹,点击进入对应歌曲列表
 */
public class FolderActivity extends AppCompatActivity {

    public static final String EXTRA_FOLDER_PATH = "folder_path";
    public static final String EXTRA_FOLDER_NAME = "folder_name";

    private RecyclerView rvFolders;
    private TextView tvEmpty;
    private FolderAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_folder);

        rvFolders = findViewById(R.id.rv_folders);
        tvEmpty = findViewById(R.id.tv_folder_empty);
        Button btnBack = findViewById(R.id.btn_back);
        btnBack.setOnClickListener(v -> finish());

        adapter = new FolderAdapter(this);
        adapter.setOnItemClickListener((position, folder) -> {
            // 启动文件夹歌曲列表,复用 MainActivity?这里直接返回结果让 MainActivity 处理
            Intent data = new Intent();
            data.putExtra(EXTRA_FOLDER_PATH, folder.path);
            data.putExtra(EXTRA_FOLDER_NAME, folder.name);
            setResult(RESULT_OK, data);
            finish();
        });
        rvFolders.setLayoutManager(new LinearLayoutManager(this));
        rvFolders.setAdapter(adapter);

        loadFolders();
    }

    /** 从全局缓存读取扫描结果并分组 */
    private void loadFolders() {
        List<MusicBean> all = MusicDataHolder.getInstance().getMusicList();
        List<FolderScanner.Folder> folders = FolderScanner.groupByFolder(all);
        adapter.setData(folders);
        if (folders.isEmpty()) {
            tvEmpty.setVisibility(View.VISIBLE);
            tvEmpty.setText("未找到音乐文件夹");
        } else {
            tvEmpty.setVisibility(View.GONE);
        }
    }
}