package com.leonardorozza.mvgrreparacionesbackend.config;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.User;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.UserRole;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DataLoader implements CommandLineRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${admin.user}")
    private String username;

    @Value("${admin.password}")
    private String password;

    @Override
    public void run(String... args) {

        if (userRepository.count() == 0) {

            User admin = User.builder()
                    .username(username)
                    .password(passwordEncoder.encode(password))
                    .email("admin@mvgr.com")
                    .role(UserRole.ADMIN)
                    .active(true)
                    .build();

            userRepository.save(admin);

            System.out.println("=== ADMIN creado correctamente ===");
        }
    }
}
