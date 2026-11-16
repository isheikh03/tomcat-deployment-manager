package com.tomcatmanager.access;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UserTeamRepository extends JpaRepository<UserTeam, Long> {
    List<UserTeam> findByUserId(Long userId);
    List<UserTeam> findByTeamId(Long teamId);
    boolean existsByUserIdAndTeamId(Long userId, Long teamId);
}
