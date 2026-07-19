package me.one_org.chatControlePlane.entitys;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.cassandra.core.mapping.PrimaryKey;
import org.springframework.data.cassandra.core.mapping.Table;

import java.time.LocalDate;

@Table("users")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User {
    @PrimaryKey
    @NotBlank(message = "id is required")
    private String id;
    @NotBlank(message = "name is required")
    private String name;
    @NotBlank(message = "email is required")
    @Email(message = "invalid email format")
    private String email;
    private String profilePicture;
    @NotBlank(message = "description is required")
    private String description;
    @NotBlank(message = "dob can't be null")
    private LocalDate dob;
}
