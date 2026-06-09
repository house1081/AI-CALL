package com.aicall.controller;

import com.aicall.common.Result;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/upload")
public class FileUploadController {

    @PostMapping("/voucher")
    public Result<Map<String, String>> uploadVoucher(@RequestParam("file") MultipartFile file) throws IOException {
        Path dir = Paths.get("./uploads/voucher/");
        Files.createDirectories(dir);
        String ext = file.getOriginalFilename() != null && file.getOriginalFilename().contains(".")
                ? file.getOriginalFilename().substring(file.getOriginalFilename().lastIndexOf('.')) : ".jpg";
        String name = UUID.randomUUID() + ext;
        Files.write(dir.resolve(name), file.getBytes());
        return Result.ok(Map.of("url", "/uploads/voucher/" + name));
    }
}
