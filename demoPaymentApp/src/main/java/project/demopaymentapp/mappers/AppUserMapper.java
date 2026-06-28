package project.demopaymentapp.mappers;

import org.apache.ibatis.annotations.*;
import project.demopaymentapp.model.AppUser;

import java.util.Optional;

@Mapper
public interface AppUserMapper {
    @Select("SELECT * FROM app_users WHERE id = #{id}")
    Optional<AppUser> findById(Long id);

    @Select("SELECT * FROM app_users WHERE email = #{email}")
    Optional<AppUser> findByEmail(String email);

    @Select("SELECT COUNT(*) > 0 FROM app_users WHERE email = #{email}")
    boolean existsByEmail(String email);

    @Insert("INSERT INTO app_users (name, email, password_hash, created_at)" +
            "VALUES (#{name}, #{email}, #{passwordHash}), NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert (AppUser user);

    @Update("UPDATE app_users SET name = #{name}, email = #{email} WHERE id = #{id}")
    void update (AppUser user);

    @Delete("DELETE FROM app_users WHERE id = #{id}")
    void delete (Long id);
}
