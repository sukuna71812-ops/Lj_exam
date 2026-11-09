package University.exam.util;

import java.util.regex.Pattern;

public class PasswordUtil {

    private static final Pattern BCRYPT_PATTERN = Pattern.compile("^\\$2[aby]\\$\\d{2}\\$[./A-Za-z0-9]{53}$");

    public static boolean isBCrypt(String password) {
        if (password == null) {
            return false;
        }
        return BCRYPT_PATTERN.matcher(password).matches();
    }
}

