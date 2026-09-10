package com.uteq.software.labrumiologia;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.viewpager2.widget.ViewPager2;

import com.uteq.software.labrumiologia.data.EquipmentRepository;
import com.uteq.software.labrumiologia.model.EquipmentInfo;
import com.uteq.software.labrumiologia.ui.ImagePagerAdapter;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class EquipmentDetailActivity extends AppCompatActivity {
    private String equipmentId;
    private String equipmentLabel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_equipment_detail);

        equipmentId = getIntent().getStringExtra(DetectionActivity.EXTRA_EQUIPMENT_ID);
        equipmentLabel = getIntent().getStringExtra(DetectionActivity.EXTRA_EQUIPMENT_LABEL);
        
        TextView title = findViewById(R.id.equipmentTitle);
        TextView nameSub = findViewById(R.id.equipmentNameSub);
        ViewPager2 viewPager = findViewById(R.id.equipmentViewPager);
        TextView imageIndicator = findViewById(R.id.imageIndicator);
        ImageView thumbImage = findViewById(R.id.equipmentThumb);
        
        TextView brandTop = findViewById(R.id.equipmentBrandTop);
        TextView classYolo = findViewById(R.id.equipmentClassYolo);
        
        TextView description = findViewById(R.id.equipmentDescription);
        TextView temp = findViewById(R.id.equipmentTemp);
        TextView components = findViewById(R.id.equipmentComponents);
        TextView function = findViewById(R.id.equipmentFunction);
        TextView brandBottom = findViewById(R.id.equipmentBrand);
        TextView usage = findViewById(R.id.equipmentUsage);
        TextView safety = findViewById(R.id.equipmentSafety);

        EquipmentInfo info = equipmentId != null ? new EquipmentRepository(this).get(equipmentId) : null;
        
        List<String> imagePaths = getCatalogPhotos(equipmentId);
        ImagePagerAdapter adapter = new ImagePagerAdapter(imagePaths);
        viewPager.setAdapter(adapter);
        
        if (!imagePaths.isEmpty()) {
            imageIndicator.setText(String.format(Locale.getDefault(), "1/%d", imagePaths.size()));
            viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
                @Override
                public void onPageSelected(int position) {
                    imageIndicator.setText(String.format(Locale.getDefault(), "%d/%d", position + 1, imagePaths.size()));
                }
            });
        } else {
            imageIndicator.setVisibility(android.view.View.GONE);
        }

        if (info != null) {
            equipmentLabel = info.name;
            title.setText(info.name);
            nameSub.setText(info.name);
            brandTop.setText(getString(R.string.marca_label, info.brand != null ? info.brand : "N/A"));
            classYolo.setText(getString(R.string.clase_yolo_label, info.id));
            
            description.setText(info.description != null ? info.description : "-");
            temp.setText(info.tempRange != null ? info.tempRange : "N/A");
            components.setText(join(info.components));
            function.setText(info.function);
            brandBottom.setText(info.brand != null ? info.brand : "N/A");
            usage.setText(info.usage != null ? info.usage : getString(R.string.info_not_available));
            safety.setText(info.safety != null ? info.safety : getString(R.string.info_not_available));
        } else {
            title.setText(equipmentLabel != null ? equipmentLabel : equipmentId);
            nameSub.setText(equipmentLabel != null ? equipmentLabel : equipmentId);
            brandTop.setText(getString(R.string.marca_label, "N/A"));
            classYolo.setText(getString(R.string.clase_yolo_label, equipmentId != null ? equipmentId : "N/A"));
            
            description.setText(R.string.ficha_missing);
            temp.setText("N/A");
            components.setText("-");
            function.setText("-");
            brandBottom.setText("N/A");
            usage.setText(R.string.info_not_available);
            safety.setText(R.string.info_not_available);
        }

        bindCatalogPhoto(thumbImage, equipmentId);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        
        findViewById(R.id.btnChat).setOnClickListener(v -> {
            Intent i = new Intent(this, ChatActivity.class);
            i.putExtra(DetectionActivity.EXTRA_EQUIPMENT_ID, equipmentId);
            i.putExtra(DetectionActivity.EXTRA_EQUIPMENT_LABEL, equipmentLabel);
            startActivity(i);
        });
    }

    private List<String> getCatalogPhotos(String classId) {
        List<String> paths = new ArrayList<>();
        if (classId == null) return paths;
        
        String basePath = "equipment_photos/";
        String[] suffixes = {"", "_2", "_3", "_4"};
        
        for (String suffix : suffixes) {
            String path = basePath + classId + suffix + ".jpg";
            try (InputStream in = getAssets().open(path)) {
                paths.add(path);
            } catch (IOException ignored) {}
        }
        
        return paths;
    }

    private void bindCatalogPhoto(ImageView imageView, String classId) {
        if (classId == null) return;
        String assetPath = "equipment_photos/" + classId + ".jpg";
        try (InputStream in = getAssets().open(assetPath)) {
            Bitmap ref = BitmapFactory.decodeStream(in);
            if (ref != null) {
                imageView.setImageBitmap(ref);
            }
        } catch (IOException ignored) {
        }
    }

    private static String join(List<String> items) {
        if (items == null || items.isEmpty()) return "-";
        StringBuilder sb = new StringBuilder();
        for (String s : items) {
            sb.append("• ").append(s).append('\n');
        }
        return sb.toString().trim();
    }
}
