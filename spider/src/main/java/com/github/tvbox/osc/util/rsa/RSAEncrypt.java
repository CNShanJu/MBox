package com.github.tvbox.osc.util.rsa;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.security.Key;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.Cipher;

/**
 * RSA 非对称加密算法，加解密工具类，
 * 加密长度 不能超过 128 个字节。
 */
public class RSAEncrypt {

    public static final String TAG = RSAEncrypt.class.getSimpleName() + " --> ";

    /**
     * 标准 jdk 加密填充方式，加解密算法/工作模式/填充方式
     */
    public static final String ECB_PKCS1_PADDING = "RSA/ECB/PKCS1Padding";

    /**
     * RSA 加密算法
     */
    public static final String KEY_ALGORITHM = "RSA";

    /**
     * RSA 最大加密明文大小
     */
    private static final int MAX_ENCRYPT_BLOCK = 117;
    /**
     * RSA最大解密密文大小
     */
    private static final int MAX_DECRYPT_BLOCK = 128;

    /**
     * RSA 分段加解密公共核心(与 {@code Crypto.rsa} 共用同一套块长/分段逻辑)。
     *
     * @param key      公钥或私钥
     * @param encrypt  true=加密, false=解密
     * @param config   Cipher 算法串(如 {@link #ECB_PKCS1_PADDING})
     * @param input    待处理字节
     * @param blockLen 每段长度(加密=模长/8-11, 解密=模长/8)
     */
    public static byte[] processRsaBlock(Key key, boolean encrypt, String config, byte[] input, int blockLen) throws Exception {
        Cipher cipher = Cipher.getInstance(config);
        cipher.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, key);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int offSet = 0;
        while (offSet < input.length) {
            int len = Math.min(blockLen, input.length - offSet);
            out.write(cipher.doFinal(input, offSet, len));
            offSet += len;
        }
        return out.toByteArray();
    }

    /**
     * 随机生成 RSA 密钥对
     *
     * @param keyLength 密钥长度，范围：512～2048，一般：1024
     */
    public static KeyPair getKeyPair(int keyLength) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(KEY_ALGORITHM);
            generator.initialize(keyLength);
            return generator.genKeyPair();
        } catch (Exception e) {
            handleException(e);
        }
        return null;
    }

    /**
     * 获取公钥 Base64 编码
     *
     * @param publicKey 公钥
     */
    public static String getPublicKeyBase64(PublicKey publicKey) {
        return DataUtils.base64Encode(publicKey.getEncoded());
    }

    /**
     * 获取私钥 Base64 编码
     *
     * @param privateKey 公钥
     */
    public static String getPrivateKeyBase64(PrivateKey privateKey) {
        return DataUtils.base64Encode(privateKey.getEncoded());
    }

    /**
     * 获取 PublicKey 对象
     *
     * @param pubKey 公钥，X509 格式de
     */
    public static PublicKey getPublicKey(String pubKey) {
        try {
            // 将公钥进行 Base64 解码  创建 PublicKey 对象并返回
            return KeyFactory.getInstance(KEY_ALGORITHM).generatePublic(new X509EncodedKeySpec(DataUtils.base64Decode(pubKey)));
        } catch (NoSuchAlgorithmException unused) {
            handleException(new Exception("无此算法"));
        } catch (InvalidKeySpecException unused2) {
            handleException(new Exception("公钥非法"));
        } catch (NullPointerException unused3) {
            handleException(new Exception("公钥数据为空"));
        }
        return null;
    }

    /**
     * 获取 PrivateKey 对象
     *
     * @param prvKey 私钥，PKCS8 格式
     */
    public static PrivateKey getPrivateKey(String prvKey) {
        try {
            // 将私钥进行 Base64 解码  创建 PrivateKey 对象并返回
            return KeyFactory.getInstance(KEY_ALGORITHM).generatePrivate(new PKCS8EncodedKeySpec(DataUtils.base64Decode(prvKey)));
        } catch (NoSuchAlgorithmException unused) {
            handleException(new Exception("无此算法"));
        } catch (InvalidKeySpecException unused2) {
            handleException(new Exception("私钥非法"));
        } catch (NullPointerException unused3) {
            handleException(new Exception("私钥数据为空"));
        }
        return null;
    }

    // --------------------- 1 公钥加密，私钥解密 ---------------------

    /**
     * 使用公钥将数据进行分段加密
     *
     * @param data   要加密的数据
     * @param pubKey 公钥 Base64 字符串，X509 格式
     * @return 加密后的 Base64 编码数据，加密失败返回 null
     */
    public static String encryptByPublicKey(String data, String pubKey, int mlong, boolean block) {
        return encryptByPublicKey(data, pubKey, ECB_PKCS1_PADDING, mlong, block);
    }

    public static String encryptByPublicKey(String data, String pubKey, String config, int mlong, boolean block) {
        try {
            byte[] bytes = data.getBytes("UTF-8");
            // 创建 Cipher 对象
            Cipher cipher = Cipher.getInstance(config);
            // 初始化 Cipher 对象，加密模式
            RSAPublicKey rSAPublicKey = (RSAPublicKey) getPublicKey(pubKey);
            cipher.init(Cipher.ENCRYPT_MODE, rSAPublicKey);
            if(mlong == 1){
                return DataUtils.base64Encode(cipher.doFinal(bytes));
            }
            int bitLength = block ? rSAPublicKey.getModulus().bitLength() / 8 - 11 : MAX_ENCRYPT_BLOCK;
            // 将加密后的数据转换成 Base64 字符串
            return DataUtils.base64Encode(processRsaBlock(rSAPublicKey, true, config, bytes, bitLength));
        } catch (Exception e) {
            handleException(e);
        }
        return null;
    }

    /**
     * 使用私钥将加密后的 Base64 字符串进行分段解密
     *
     *
     * @param encryptBase64Data 加密后的 Base64 字符串
     * @param prvKey            私钥 Base64 字符串，PKCS8 格式
     * @return 解密后的明文，解密失败返回 null
     */
    public static String decryptByPrivateKey(String encryptBase64Data, String prvKey, int mlong, boolean block) {
        return decryptByPrivateKey(encryptBase64Data, prvKey, ECB_PKCS1_PADDING, mlong, block);
    }

    public static String decryptByPrivateKey(String encryptBase64Data, String prvKey, String config, int mlong, boolean block) {
        try {
            // 将要解密的数据，进行 Base64 解码
            byte[] encryptedData = DataUtils.base64Decode(encryptBase64Data);
            // 创建 Cipher 对象，用来解密
            Cipher cipher = Cipher.getInstance(config);
            // 初始化 Cipher 对象，解密模式
            RSAPrivateKey rSAPrivateKey = (RSAPrivateKey) getPrivateKey(prvKey);
            cipher.init(Cipher.DECRYPT_MODE, rSAPrivateKey);
            if(mlong == 1){
                return new String(cipher.doFinal(encryptedData));
            }
            int bitLength = block ? rSAPrivateKey.getModulus().bitLength() / 8 : MAX_DECRYPT_BLOCK;
            return new String(processRsaBlock(rSAPrivateKey, false, config, encryptedData, bitLength), "UTF-8");
        } catch (Exception e) {
            handleException(e);
        }
        return null;
    }

    // --------------------- 2 私钥加密，公钥解密 ---------------------

    /**
     * 使用 私钥 将数据进行分段加密
     *
     * @param data   要加密的数据
     * @param prvKey 私钥 Base64 字符串，PKCS8 格式
     * @return 加密后的 Base64 编码数据，加密失败返回 null
     */
    public static String encryptByPrivateKey(String data, String prvKey, int mlong, boolean block) {
        return encryptByPrivateKey(data, prvKey, ECB_PKCS1_PADDING, mlong, block);
    }

    public static String encryptByPrivateKey(String data, String prvKey, String config, int mlong, boolean block) {
        try {
            byte[] bytes = data.getBytes("UTF-8");
            // 创建 Cipher 对象
            Cipher cipher = Cipher.getInstance(config);
            // 初始化 Cipher 对象，加密模式
            RSAPrivateKey rSAPrivateKey = (RSAPrivateKey) getPrivateKey(prvKey);
            cipher.init(Cipher.ENCRYPT_MODE, rSAPrivateKey);
            if(mlong == 1){
                return DataUtils.base64Encode(cipher.doFinal(bytes));
            }
            int bitLength = block ? rSAPrivateKey.getModulus().bitLength() / 8 - 11 : MAX_ENCRYPT_BLOCK;
            // 将加密后的数据转换成 Base64 字符串
            return DataUtils.base64Encode(processRsaBlock(rSAPrivateKey, true, config, bytes, bitLength));
        } catch (Exception e) {
            handleException(e);
        }
        return null;
    }

    /**
     * 使用 公钥 将加密后的 Base64 字符串进行分段解密
     *
     * @param encryptBase64Data 加密后的 Base64 字符串
     * @param pubKey            公钥 Base64 字符串，X509 格式
     * @return 解密后的明文，解密失败返回 null
     */

    public static String decryptByPublicKey(String encryptBase64Data, String pubKey, int mlong, boolean block) {
        return decryptByPublicKey(encryptBase64Data, pubKey, ECB_PKCS1_PADDING, mlong, block);
    }
    public static String decryptByPublicKey(String encryptBase64Data, String pubKey, String config, int mlong, boolean block) {
        try {
            // 将要解密的数据，进行 Base64 解码
            byte[] encryptedData = DataUtils.base64Decode(encryptBase64Data);
            // 创建 Cipher 对象，用来解密
            Cipher cipher = Cipher.getInstance(config);
            // 初始化 Cipher 对象，解密模式
            RSAPublicKey rSAPublicKey = (RSAPublicKey) getPublicKey(pubKey);
            cipher.init(Cipher.DECRYPT_MODE, rSAPublicKey);
            if(mlong == 1){
                return new String(cipher.doFinal(encryptedData));
            }
            int bitLength = block ? rSAPublicKey.getModulus().bitLength() / 8 : MAX_DECRYPT_BLOCK;
            return new String(processRsaBlock(rSAPublicKey, false, config, encryptedData, bitLength), "UTF-8");
        } catch (Exception e) {
            handleException(e);
        }
        return null;
    }

    /**
     * 处理异常
     */
    private static void handleException(Exception e) {
        e.printStackTrace();
        Log.e(TAG, TAG + e);
    }
}