import java.lang.reflect.Constructor;
public class FindConstructors {
    public static void main(String[] args) throws Exception {
        Class<?> clazz = Class.forName("org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer");
        for (Constructor<?> c : clazz.getConstructors()) {
            System.out.println(c);
        }
    }
}
