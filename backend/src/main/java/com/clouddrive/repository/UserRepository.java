package com.clouddrive.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.clouddrive.entity.User;

/**
 * 用户数据访问（对齐 Go userRepo）。
 */
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);

    Optional<User> findByEmail(String email);

    /** 配额原子累加：storage_used = storage_used + delta（对齐 gorm.Expr）。 */
    @Modifying
    @Query("update User u set u.storageUsed = u.storageUsed + :delta where u.id = :id")
    void addStorageUsed(@Param("id") Long id, @Param("delta") long delta);

    @Modifying
    @Query("update User u set u.username = :username where u.id = :id")
    void updateUsername(@Param("id") Long id, @Param("username") String username);

    @Modifying
    @Query("update User u set u.password = :password where u.id = :id")
    void updatePassword(@Param("id") Long id, @Param("password") String password);
}
