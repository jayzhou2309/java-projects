package project.demopaymentapp.mappers;

import org.apache.ibatis.annotations.*;
import project.demopaymentapp.model.Account;

import java.util.Optional;

@Mapper
public interface AccountMapper {
    @Select("SELECT * FROM accounts WHERE id = #{id}")
    Optional<Account> findById(Long id);

    @Select("SELECT * FROM accounts WHERE user_Id = #{userId}")
    Optional<Account> findByUserId(Long userId);

    @Insert("INSERT INTO accounts (user_id, balance, currency, created_at)" +
            "VALUES(#{userId}, #{balance}, ${currency}, NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(Account account);

    @Update("UPDATE accounts SET balance = #{balance} WHERE id = #{id}")
    void updateBalance(Account account);
}
