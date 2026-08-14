package com.example.hsmdemo.web;

import com.example.hsmdemo.dto.CryptoResponse;
import com.example.hsmdemo.dto.DataRequest;
import com.example.hsmdemo.dto.VerifyRequest;
import com.example.hsmdemo.service.CryptoService;
import org.springframework.web.bind.annotation.*;

import java.util.Base64;
import java.util.Map;

/**
 * 统一 REST 入口。走哪种后端由激活的 profile 决定（kms / jce / pkcs11），
 * Controller 只依赖 {@link CryptoService} 接口，不关心实现。
 */
@RestController
@RequestMapping("/api")
public class CryptoController {

    private final CryptoService crypto;

    public CryptoController(CryptoService crypto) {
        this.crypto = crypto;
    }

    @GetMapping("/info")
    public Map<String, Object> info() {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("backend", crypto.backend());
        out.put("capabilities", crypto.capabilities());
        Map<String, String> identity = crypto.identity();
        if (identity != null) {
            out.put("identity", identity);
        }
        return out;
    }

    @PostMapping("/sign")
    public CryptoResponse sign(@RequestBody DataRequest req) {
        byte[] sig = crypto.sign(decode(req.dataBase64()));
        return CryptoResponse.of(crypto.backend(), "sign", sig);
    }

    @PostMapping("/verify")
    public CryptoResponse verify(@RequestBody VerifyRequest req) {
        boolean ok = crypto.verify(decode(req.dataBase64()), decode(req.signatureBase64()));
        return CryptoResponse.verify(crypto.backend(), ok);
    }

    @PostMapping("/encrypt")
    public CryptoResponse encrypt(@RequestBody DataRequest req) {
        byte[] ct = crypto.encrypt(decode(req.dataBase64()));
        return CryptoResponse.of(crypto.backend(), "encrypt", ct);
    }

    @PostMapping("/decrypt")
    public CryptoResponse decrypt(@RequestBody DataRequest req) {
        byte[] pt = crypto.decrypt(decode(req.dataBase64()));
        return CryptoResponse.of(crypto.backend(), "decrypt", pt);
    }

    @PostMapping("/encrypt-asym")
    public CryptoResponse encryptAsym(@RequestBody DataRequest req) {
        byte[] ct = crypto.encryptAsym(decode(req.dataBase64()));
        return CryptoResponse.of(crypto.backend(), "encryptAsym", ct);
    }

    @PostMapping("/decrypt-asym")
    public CryptoResponse decryptAsym(@RequestBody DataRequest req) {
        byte[] pt = crypto.decryptAsym(decode(req.dataBase64()));
        return CryptoResponse.of(crypto.backend(), "decryptAsym", pt);
    }

    private static byte[] decode(String base64) {
        if (base64 == null) {
            throw new IllegalArgumentException("缺少 Base64 数据");
        }
        return Base64.getDecoder().decode(base64);
    }
}
