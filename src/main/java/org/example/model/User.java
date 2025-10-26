package org.example.model;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class User {
    private Long id;
    private String login;
    private String password;

    public User() {}

    public User(Long id, String login, String password) {
        this.id = id;
        this.login = login;
        this.password = password;
    }
}
