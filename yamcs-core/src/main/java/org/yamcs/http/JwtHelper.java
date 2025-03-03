package org.yamcs.http;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.yamcs.security.User;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

public class JwtHelper {

    private static final String NO_ALG_HEADER = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("{\"alg\":\"none\"}".getBytes());

    private static final String HS256_HEADER = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("{\"alg\":\"HS256\"}".getBytes());

    /**
     * Generates an unsigned JSON Web Token.
     */
    public static String generateUnsignedToken(User user, int ttl) {
        String joseHeader = NO_ALG_HEADER;
        String payload = generatePayload(user, ttl);
        return joseHeader + "." + payload + ".";
    }

    /**
     * Generates a signed JSON Web Token appended with a signature which can be used to validate the JWT by whoever
     * knows the specified secret.
     */
    public static String generateHS256Token(User user, byte[] secret, int ttl)
            throws InvalidKeyException, NoSuchAlgorithmException {
        String joseHeader = HS256_HEADER;
        String payload = generatePayload(user, ttl);
        String unsignedToken = joseHeader + "." + payload; // Without trailing period

        String signature = hmacSha256(secret, unsignedToken);
        return joseHeader + "." + payload + "." + signature;
    }

    private static String generatePayload(User user, int ttl) {
        JsonObject claims = new JsonObject();

        // Standard JWT props (aka Registered Claims)
        claims.addProperty("iss", "Yamcs"); // Issuer of the JWT token
        claims.addProperty("sub", user.getName()); // Subject
        long now = System.currentTimeMillis() / 1000;
        claims.addProperty("iat", now); // Issued at (Time at issuer)
        if (ttl >= 0) {
            claims.addProperty("exp", now + ttl); // Expires at (Time at issuer)
        }

        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(claims.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String hmacSha256(byte[] secret, String data) throws NoSuchAlgorithmException, InvalidKeyException {
        Mac hmac = Mac.getInstance("HmacSHA256");
        hmac.init(new SecretKeySpec(secret, "HmacSHA256"));
        byte[] macResult = hmac.doFinal(data.getBytes());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(macResult);
    }

    public static JsonObject decodeUnverified(String token) throws JwtDecodeException {
        String parts[] = token.split("\\.");

        byte[] decodedClaims;
        try {
            decodedClaims = Base64.getUrlDecoder().decode(parts[1].getBytes());
        } catch (IllegalArgumentException e) {
            throw new JwtDecodeException("Could not decode JWT Payload as Base 64 URL-encoded String", e);
        }

        try {
            return new JsonParser().parse(new String(decodedClaims, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (JsonSyntaxException e) {
            throw new JwtDecodeException("Could not decode JWT Payload as JSON");
        } catch (IllegalStateException e) {
            throw new JwtDecodeException("Decoded JWT Payload is not a valid JSON Object");
        }
    }

    public static JsonObject decode(String token, byte[] secret)
            throws JwtDecodeException, InvalidKeyException, NoSuchAlgorithmException {
        String parts[] = token.split("\\.");
        if (parts.length < 2) {
            throw new JwtDecodeException("JWT should consist of three sections separated by dots");
        }

        String unsignedToken = parts[0] + "." + parts[1];
        byte[] expectedSignature = hmacSha256(secret, unsignedToken).getBytes();
        if (parts.length < 3) {
            throw new JwtDecodeException("Signature missing");
        }
        byte[] actualSignature = parts[2].getBytes();
        if (!Arrays.equals(expectedSignature, actualSignature)) {
            throw new JwtDecodeException("Invalid signature");
        }

        byte[] decodedClaims;
        try {
            decodedClaims = Base64.getUrlDecoder().decode(parts[1].getBytes());
        } catch (IllegalArgumentException e) {
            throw new JwtDecodeException("Could not decode JWT Payload as Base 64 URL-encoded UTF-8 String", e);
        }

        try {
            return new JsonParser().parse(new String(decodedClaims, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (JsonSyntaxException e) {
            throw new JwtDecodeException("Could not decode JWT Payload as JSON");
        } catch (IllegalStateException e) {
            throw new JwtDecodeException("Decoded JWT Payload is not a valid JSON Object");
        }
    }

    @SuppressWarnings("serial")
    public static final class JwtDecodeException extends Exception {

        public JwtDecodeException(String message) {
            super(message);
        }

        public JwtDecodeException(String message, Throwable e) {
            super(message, e);
        }
    }
}
