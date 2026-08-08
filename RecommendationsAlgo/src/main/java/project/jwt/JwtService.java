package project.jwt;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;

@Service
@RequiredArgsConstructor
public class JwtService {
    private final JwtProperties jwtProperties;

    // Get Signing Key
    private SecretKey getSigningKey(){
        byte[] bytekey = Decoders.BASE64.decode(jwtProperties.getSecret());
        return Keys.hmacShaKeyFor(bytekey);
    }

    private String generateAccessToken( ){

    }

}
