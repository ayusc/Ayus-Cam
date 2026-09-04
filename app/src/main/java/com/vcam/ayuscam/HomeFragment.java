private void handleMediaResult(Uri uri, String type) {
        if (uri == null) return;
        String displayName = "media_" + System.currentTimeMillis();
        
        try (android.database.Cursor cursor = requireContext().getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (nameIndex != -1) displayName = cursor.getString(nameIndex);
            }
        } catch (Exception ignored) {}
        
        File targetDir = new File(AppConfig.BASE_DIR);
        if (!targetDir.exists()) targetDir.mkdirs();
        
        String ext = "IMAGE".equals(type) ? ".jpg" : ".mp4";
        File localFile = new File(targetDir, System.currentTimeMillis() + ext);
        
        try (java.io.InputStream in = requireContext().getContentResolver().openInputStream(uri);
             java.io.OutputStream out = new java.io.FileOutputStream(localFile)) {
            
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
            
            config.mediaPaths.add(localFile.getAbsolutePath());
            config.mediaTypes.add(type);
            config.mediaNames.add(displayName);
            config.selectedIndex = config.mediaPaths.size() - 1;
            
            // This now saves directly to /sdcard/DCIM/Camera1/
            config.save();
            
            updateUI();
            restartPreviewMode();
        } catch (Exception e) {
            Toast.makeText(requireContext(), "Failed to load file: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
