package com.cdwater.toolshare.module.auth;

import com.cdwater.toolshare.shared.web.Result;
import com.cdwater.toolshare.module.auth.UserService;
import com.cdwater.toolshare.module.auth.ProfileUpdateDTO;
import com.cdwater.toolshare.module.auth.UserVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserProfileController {

    private final UserService userService;

    @GetMapping("/profile")
    public Result<UserVO> profile() {
        return Result.success(userService.profile());
    }

    @PutMapping("/profile")
    public Result<UserVO> update(@RequestBody ProfileUpdateDTO dto) {
        return Result.success(userService.updateProfile(dto));
    }
}
