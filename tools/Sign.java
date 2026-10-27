import com.android.apksig.ApkSigner;

import java.io.File;
import java.io.FileInputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Sign {
    public static void main(String[] args) throws Exception {
        String in = args[0];
        String out = args[1];
        String ksPath = args[2];
        String alias = args[3];
        String pass = args[4];

        KeyStore ks = KeyStore.getInstance("PKCS12");
        FileInputStream fis = new FileInputStream(ksPath);
        ks.load(fis, pass.toCharArray());
        fis.close();

        PrivateKey key = (PrivateKey) ks.getKey(alias, pass.toCharArray());
        java.security.cert.Certificate[] chain = ks.getCertificateChain(alias);
        List<X509Certificate> certs = new ArrayList<X509Certificate>();
        for (java.security.cert.Certificate c : chain) {
            certs.add((X509Certificate) c);
        }

        ApkSigner.SignerConfig cfg =
                new ApkSigner.SignerConfig.Builder("WUWA", key, certs).build();
        new ApkSigner.Builder(Collections.singletonList(cfg))
                .setInputApk(new File(in))
                .setOutputApk(new File(out))
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .setMinSdkVersion(21)
                .build()
                .sign();
        System.out.println("signed -> " + out);
    }
}
