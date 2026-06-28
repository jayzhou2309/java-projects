package project.demopaymentapp.mappers;

import org.apache.ibatis.annotations.*;
import project.demopaymentapp.model.Account;
import project.demopaymentapp.model.Transaction;
import project.demopaymentapp.model.TransactionStatus;

import java.util.List;
import java.util.Optional;

@Mapper
public interface TransactionMapper {
    @Select("SELECT * FROM transactions WHERE id = #{id}")
    Optional<Transaction> findById(Long id);

    @Select("SELECT * FROM transactions WHERE sender_account_id = #{accountId}" +
            "OR receiver_account_id = #{accountId} ORDER BY created_at DESC")
    List<Transaction> findByAccountId(Long accountId);

    @Insert("INSERT INTO transactions (sender_account_id, reciever_account_id, amount, status, created_at)" +
            "VALUES (#{senderAccountId}, #{receiverAccountId}, #{amount}, #{status}, NOW())")
    void insert(Transaction transaction);

    @Update("UPDATE transactions SET status = #{status} where id = #{id}")
    void updateStatus(@Param("id") Long id, @Param("status") TransactionStatus status);


}
