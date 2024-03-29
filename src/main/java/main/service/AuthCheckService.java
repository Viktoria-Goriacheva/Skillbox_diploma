package main.service;

import com.github.cage.Cage;
import com.github.cage.GCage;
import main.api.request.ChangePasswordRequest;
import main.api.request.EditProfileRequest;
import main.api.request.EditProfileWithoutPhotoRequest;
import main.api.response.CaptchaResponse;
import main.api.response.PostImageResponse;
import main.api.response.ResponseWithErrors;
import main.dto.UserForRegistrationDto;
import main.dto.errorMessages.ErrorsForProfile;
import main.model.CaptchaCode;
import main.model.User;
import main.repository.CaptchaCodeRepository;
import main.repository.UserRepository;
import main.security.SecurityUser;
import net.coobird.thumbnailator.Thumbnails;
import org.apache.commons.lang3.RandomStringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.servlet.http.HttpServletRequest;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

@Service
public class AuthCheckService {

    @Value("${captcha.lifetimeInMinutes}")
    private int captchaLifetime;
    @Value("${upload.path}")
    private String uploadPath;
    private final CaptchaCodeRepository captchaCodeRepository;
    private final UserRepository userRepository;
    private final DefaultEmailService defaultEmailService;
    private final ServletWebServerApplicationContext webServerAppContext;

    private final PasswordEncoder passwordEncoder;


    public AuthCheckService(CaptchaCodeRepository captchaCodeRepository,
                            UserRepository userRepository,
                            DefaultEmailService defaultEmailService,
                            ServletWebServerApplicationContext webServerAppContext,
                            PasswordEncoder passwordEncoder) {
        this.captchaCodeRepository = captchaCodeRepository;
        this.userRepository = userRepository;
        this.defaultEmailService = defaultEmailService;
        this.webServerAppContext = webServerAppContext;
        this.passwordEncoder = passwordEncoder;
    }

    @Scheduled(fixedDelay = 7_200_000)
    public void deleteOldCaptcha() {
        captchaCodeRepository.deleteOldCaptcha(captchaLifetime);
    }

    public CaptchaResponse generateCaptchaCodes() {
        Cage cage = new GCage();
        String token = cage.getTokenGenerator().next();
        String encodedString = "data:image/png;base64, " + Base64.getEncoder().encodeToString(cage.draw(token));
        String secret = UUID.randomUUID().toString();
        captchaCodeRepository.save(new CaptchaCode(Calendar.getInstance().getTime(), token, secret));
        return new CaptchaResponse(secret, encodedString);
    }

    public boolean checkCaptcha(String secretCode) {
        return captchaCodeRepository.findBySecretCode(secretCode) != null;
    }

    public ResponseWithErrors addNewUser(UserForRegistrationDto userDTO) {
        Map<String, String> errors = new HashMap<>();
        isEmailValid(userDTO.getEmail(), "", errors);
        isNameCorrect(userDTO.getName(), errors);
        isPasswordCorrect(userDTO.getPassword(), errors);
        isCaptchaCorrect(userDTO.getCaptchaSecret(), userDTO.getCaptcha(), errors);

        if (errors.isEmpty()) {
            User user = new User(false,
                    Calendar.getInstance().getTime(),
                    userDTO.getName(),
                    userDTO.getEmail(),
                    passwordEncoder.encode(userDTO.getPassword()),
                    userDTO.getCaptcha(),
                    null);
            userRepository.save(user);
            return new ResponseWithErrors(true, null);
        }
        return new ResponseWithErrors(false, errors);
    }

    public PostImageResponse postImage(MultipartFile photo) throws IOException {
        Map<String, String> errors = new HashMap<>();
        if (photo != null) {
            String photoPath = savePhoto(photo, false);
            if (photoPath.length() != 0) {
                return new PostImageResponse(true, null, photoPath);
            }
        }
        errors.put("image", ErrorsForProfile.photo);
        return new PostImageResponse(false, errors, null);
    }

    public ResponseWithErrors editProfile(int userId, EditProfileRequest editProfileRequest)
            throws IOException {
        Map<String, String> errors = new HashMap<>();
        User user = userRepository.findById(userId).get();

        user.setEmail(isEmailValid(editProfileRequest.getEmail(), user.getEmail(), errors)
                ? editProfileRequest.getEmail() : user.getEmail());

        user.setName(isNameCorrect(editProfileRequest.getName(), errors)
                ? editProfileRequest.getName() : user.getName());

        user.setPassword(isPasswordCorrect(editProfileRequest.getPassword(), errors)
                ? passwordEncoder.encode(editProfileRequest.getPassword()) : user.getPassword());


        if (editProfileRequest.getPhoto() != null) {
            String photoPath = savePhoto(editProfileRequest.getPhoto(), true);

            if (photoPath.length() == 0) {
                errors.put("photo", ErrorsForProfile.photo);
            }

            File oldPhotoFile = user.getPhoto() == null ? null : new File(user.getPhoto().substring(1));

            user.setPhoto(photoPath.isEmpty() ? null : photoPath);

            if (oldPhotoFile != null
                    && Files.exists(oldPhotoFile.toPath())) {
                oldPhotoFile.delete();
            }
        }

        if (errors.isEmpty()) {
            userRepository.save(user);
            List authorities = new ArrayList();
            authorities.addAll(user.getRole().getAuthorities());
            SecurityUser securityUser = new SecurityUser(
                    user.getEmail(),
                    user.getPassword(),
                    userId,
                    authorities);
            Authentication authentication =
                    new UsernamePasswordAuthenticationToken(
                            securityUser,
                            securityUser.getPassword(),
                            securityUser.getAuthorities());
            SecurityContextHolder.getContext().setAuthentication(authentication);
            return new ResponseWithErrors(true, null);
        }
        return new ResponseWithErrors(false, errors);
    }


    public ResponseWithErrors editProfile(int userId, EditProfileWithoutPhotoRequest editProfileRequest) {
        Map<String, String> errors = new HashMap<>();
        User user = userRepository.findById(userId).get();

        user.setEmail(isEmailValid(editProfileRequest.getEmail(), user.getEmail(), errors)
                ? editProfileRequest.getEmail() : user.getEmail());

        user.setName(isNameCorrect(editProfileRequest.getName(), errors)
                ? editProfileRequest.getName() : user.getName());

        user.setPassword(isPasswordCorrect(editProfileRequest.getPassword(), errors)
                ? passwordEncoder.encode(editProfileRequest.getPassword()) : user.getPassword());

        if (editProfileRequest.getRemovePhoto() != null && editProfileRequest.getRemovePhoto()) {
            File oldPhotoFile = user.getPhoto() == null ? null : new File(user.getPhoto().substring(1));

            if (oldPhotoFile != null
                    && Files.exists(oldPhotoFile.toPath())) {
                oldPhotoFile.delete();
            }
            user.setPhoto(null);

        }

        if (errors.isEmpty()) {
            userRepository.save(user);
            List authorities = new ArrayList();
            authorities.addAll(user.getRole().getAuthorities());
            SecurityUser securityUser = new SecurityUser(
                    user.getEmail(),
                    user.getPassword(),
                    userId,
                    authorities);
            Authentication authentication =
                    new UsernamePasswordAuthenticationToken(
                            securityUser,
                            securityUser.getPassword(),
                            securityUser.getAuthorities());
            SecurityContextHolder.getContext().setAuthentication(authentication);
            return new ResponseWithErrors(true, null);
        }
        return new ResponseWithErrors(false, errors);
    }


    public Boolean restore(String email, HttpServletRequest request) throws MalformedURLException {
        User user = userRepository.findByEmail(email);
        if (user != null) {
            String hash = UUID.randomUUID().toString();
            URL url = new URL(request.getRequestURL().toString());
            int port = webServerAppContext.getWebServer().getPort();
            StringBuilder message = new StringBuilder("Перейдите по ссылке для восстановления пароля: "
                    + url.getProtocol() + "://" + url.getHost() + ":" + port + "/login/change-password/");
            message.append(hash);

            try {
                defaultEmailService.sendSimpleEmail(email, "Восстановление пароля", message.toString());
            } catch (Exception e) {
                return false;
            }

            user.setCode(hash);
            userRepository.save(user);

            return true;
        }
        return false;
    }

    public ResponseWithErrors changePassword(ChangePasswordRequest changePasswordRequest) {
        Map<String, String> errors = new HashMap<>();
        User user = userRepository.findByCode(changePasswordRequest.getCode());
        if (user != null) {
            isPasswordCorrect(changePasswordRequest.getPassword(), errors);
            isCaptchaCorrect(
                    changePasswordRequest.getCaptcha_secret(),
                    changePasswordRequest.getCaptcha(),
                    errors);

            if (errors.isEmpty()) {
                user.setPassword(passwordEncoder.encode(changePasswordRequest.getPassword()));
                userRepository.save(user);
                return new ResponseWithErrors(true, null);
            }
        }
        return new ResponseWithErrors(false, errors);
    }

    private boolean isEmailValid(String newEmail, String oldEmail, Map<String, String> map) {
        if (newEmail == null
                || (!newEmail.equals(oldEmail) && userRepository.getByEmail(newEmail) != null)) {
            map.put("email", ErrorsForProfile.email);
            return false;
        }
        return !newEmail.equals(oldEmail);
    }

    private boolean isNameCorrect(String newName, Map<String, String> map) {
        if (newName == null || !newName.chars().allMatch(Character::isLetter)) {
            map.put("name", ErrorsForProfile.name);
            return false;
        }
        return true;
    }

    private boolean isPasswordCorrect(String newPassword, Map<String, String> map) {
        if ((newPassword != null && newPassword.length() < 6)) {
            map.put("password", ErrorsForProfile.password);
            return false;
        } else return newPassword != null;
    }

    private void isCaptchaCorrect(String secret, String code, Map<String, String> map) {
        CaptchaCode captchaCode = captchaCodeRepository.findBySecretCode(secret);
        if (captchaCode == null || !captchaCode.getCode().equals(code)) {
            map.put("captcha", ErrorsForProfile.captcha);
        }
    }

    private String savePhoto(MultipartFile photo, boolean userPhoto) throws IOException {
        Path root = Paths.get(uploadPath);
        if (!Files.exists(root)) {
            Files.createDirectory(root);
        }

        if (photo != null) {
            StringBuilder photoPath = new StringBuilder(root.toString());
            String originalPhotoName = photo.getOriginalFilename();
            assert originalPhotoName != null;
            String photoFormat = originalPhotoName.substring(originalPhotoName.lastIndexOf(".") + 1);
            originalPhotoName = originalPhotoName.substring(0, originalPhotoName.lastIndexOf("."));
            if (!(photoFormat.equals("jpg") || photoFormat.equals("png"))) {
                return "";
            }

            for (int i = 0; i < 3; i++) {
                String dirName = "/" + RandomStringUtils.randomAlphanumeric(2).toLowerCase();
                File dir = new File(photoPath.append(dirName).toString());
                if (!dir.exists()) {
                    dir.mkdir();
                }
            }
            BufferedImage userImage = ImageIO.read(photo.getInputStream());
            photoPath.append("/")
                    .append(UUID.randomUUID().toString())
                    .append(originalPhotoName)
                    .append(".")
                    .append(photoFormat);
            File file = new File(photoPath.toString());
            if (userPhoto) {
                userImage = Thumbnails.of(userImage).size(30, 30).asBufferedImage();
            }
            ImageIO.write(userImage, photoFormat, file);
            photoPath.insert(0, "/");
            return photoPath.toString();
        }
        return "";
    }
}


