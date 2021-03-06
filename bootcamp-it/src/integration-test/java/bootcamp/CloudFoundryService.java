package bootcamp;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.cloudfoundry.operations.CloudFoundryOperations;
import org.cloudfoundry.operations.applications.*;
import org.cloudfoundry.operations.routes.DeleteOrphanedRoutesRequest;
import org.cloudfoundry.operations.serviceadmin.CreateServiceBrokerRequest;
import org.cloudfoundry.operations.services.*;
import org.springframework.beans.factory.config.YamlMapFactoryBean;
import org.springframework.core.io.FileSystemResource;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.*;
import java.util.function.BiConsumer;

public class CloudFoundryService {

    private Sanitizer sanitizer = new Sanitizer();

    private Log log = LogFactory.getLog(getClass());

    private final CloudFoundryOperations cf;

    public CloudFoundryService(CloudFoundryOperations cf) {
        this.cf = cf;
    }

    public void destroyOrphanedRoutes() {
        this.cf.routes()
                .deleteOrphanedRoutes(DeleteOrphanedRoutesRequest.builder().build()).block();
    }

    public void destroyApplicationUsingManifest(File file) {
        Optional.ofNullable(file).ifPresent(
                manifestFile -> applicationManifestFrom(manifestFile).forEach(
                        (f, am) -> {
                            destroyApplicationIfExists(am.getName());
                            destroyServiceIfExistsSafely(am.getName());
                            Optional.ofNullable(am.getServices()).ifPresent(
                                    svcs -> svcs.forEach(this::destroyServiceIfExistsSafely));
                            destroyOrphanedRoutes();
                        }));
    }

    private void destroyServiceIfExistsSafely(String svcName) {
        try {
            this.destroyServiceIfExists(svcName);
        } catch (Throwable th) {
            log.debug(String.format("couldn't destroy the service %s. "
                    + "Are other applications still bound to it?", svcName));
        }
    }

    public Map<String, ApplicationManifest> applicationManifestsFrom(File... files) {
        Map<String, ApplicationManifest> manifestMap = new HashMap<>();
        for (File f : files) {
            this.applicationManifestFrom(f).forEach(
                    (ff, m) -> manifestMap.put(m.getName(), m));
        }
        return manifestMap;
    }

    public void createServiceIfMissing(String svcName, String planName, String instanceName) {
        if (!this.serviceExists(instanceName)) {
            log.debug("could not find " + svcName + ", so creating it.");
            this.createService(svcName, planName, instanceName);
        }
    }

    public boolean serviceExists(String instanceName) {
        Mono<Boolean> mono = this.cf.services().listInstances()
                .filter(si -> si.getName().equals(instanceName)).singleOrEmpty()
                .hasElement();
        return mono.block();
    }

    private static <T> Optional<T> optionalIfExists(Map m, String k, Class<T> tClass) {
        return Optional.ofNullable(ifExists(m, k, tClass));
    }

    private static <T> T ifExists(Map m, String k, Class<T> tClass) {
        if (m.containsKey(k)) {
            return tClass.cast(m.get(k));
        }
        return null;
    }

    public void pushApplicationUsingManifest(File manifest) {

        this.applicationManifestFrom(manifest).forEach(
                (f, m) -> this.pushApplicationUsingManifest(f, m, true));

    }

    public void pushApplicationUsingManifest(File jarFile, ApplicationManifest manifest, boolean start) {
        pushApplicationUsingManifest(jarFile, manifest, new HashMap<>(), start);
    }

    public void createServiceBroker(String serviceBrokerName, String url, String user, String pw, boolean spaceScoped) {

        this.cf
                .serviceAdmin()
                .create(
                        CreateServiceBrokerRequest.builder().name(serviceBrokerName).password(user)
                                .username(pw).url(url).spaceScoped(spaceScoped).build()).block();
    }

    public void pushApplicationUsingManifest(File jarFile, ApplicationManifest applicationManifest,
                                             Map<String, String> envs, boolean start) {

        log.debug("pushing application " + jarFile.getAbsolutePath()
                + " using applicationManifest file " + applicationManifest.toString());
        PushApplicationRequest request = fromApplicationManifest(jarFile, applicationManifest);
        cf.applications().push(request).block();

        if (request.getNoStart() != null && request.getNoStart()) {

            Assert.notNull(applicationManifest,
                    "the applicationManifest for application " + jarFile.getAbsolutePath()
                            + " is null! Can't proceed.");

            if (applicationManifest.getServices() != null) {
                applicationManifest.getServices().forEach(
                        svc -> {
                            cf.services()
                                    .bind(
                                            BindServiceInstanceRequest.builder().applicationName(request.getName())
                                                    .serviceInstanceName(svc).build()
                                    ).block();
                            log.debug("bound service '" + svc + "' to '" + request.getName() + "'.");
                        });
            }

            BiConsumer<String, Object> consumer = (e, v) -> {
                cf.applications()
                        .setEnvironmentVariable(
                                SetEnvironmentVariableApplicationRequest.builder()
                                        .name(request.getName()).variableName(e).variableValue("" + v).build()
                        )
                        .block();
                log.debug("set environment variable '" + e + "' to the value '"
                        + this.sanitizer.sanitize(e, "" + v) + "' for application "
                        + request.getName());
            };

            Optional.ofNullable(applicationManifest.getEnvironmentVariables())
                    .ifPresent(
                            e -> applicationManifest.getEnvironmentVariables().forEach(consumer));

            Optional.ofNullable(envs).ifPresent(e -> e.forEach(consumer));

            if (start) {
                cf.applications()
                        .start(StartApplicationRequest.builder().name(request.getName()).build())
                        .block();
            }
        }
    }

    public void createUserProvidedServiceFromApplication(String appName) {
        String urlForApplication = this.urlForApplication(appName);
        boolean exists = this.serviceExists(appName);
        if (!exists) {
            this.cf
                    .services()
                    .createUserProvidedInstance(
                            CreateUserProvidedServiceInstanceRequest.builder().name(appName)
                                    .credentials(Collections.singletonMap("uri", urlForApplication)).build())
                    .block();
        } else {
            this.cf
                    .services()
                    .updateUserProvidedInstance(
                            UpdateUserProvidedServiceInstanceRequest.builder()
                                    .userProvidedServiceInstanceName(appName)
                                    .credentials(Collections.singletonMap("uri", urlForApplication)).build())
                    .block();
        }
    }

    private static class Sanitizer {

        private final Log log = LogFactory.getLog(getClass());

        private Method sanitizeMethod;

        private Object sanitizerObject;

        Sanitizer() {
            try {
                String sanitizerClass = "org.springframework.boot.actuate.endpoint.Sanitizer";
                Class<?> sanitizer = Class.forName(sanitizerClass);
                Constructor<?> ctor = sanitizer.getDeclaredConstructor();
                ctor.setAccessible(true);
                this.sanitizerObject = ctor.newInstance();
                this.sanitizeMethod = sanitizer.getMethod("sanitize", String.class,
                        Object.class);
                this.sanitizeMethod.setAccessible(true);
            } catch (Throwable th) {
                this.log.error(th);
            }
        }

        String sanitize(String k, String v) {
            try {
                return String.class.cast(sanitizeMethod.invoke(sanitizerObject,
                        "" + k.toLowerCase(), v));
            } catch (Exception e) {
                log.debug("couldn't sanitize value for key " + k + ".");
                log.error(e);
            }
            return v;
        }
    }

    public void pushApplicationAndCreateUserDefinedServiceUsingManifest(
            File manifestFile) {
        Map<File, ApplicationManifest> applicationManifestMap = this
                .applicationManifestFrom(manifestFile);
        applicationManifestMap
                .forEach(this::pushApplicationAndCreateUserDefinedServiceUsingManifest);
    }

    public void pushApplicationAndCreateUserDefinedServiceUsingManifest(File jar,
                                                                        ApplicationManifest manifest) {
        this.pushApplicationUsingManifest(jar, manifest, true);
        this.createUserProvidedServiceFromApplication(manifest.getName());
    }

    public Map<File, ApplicationManifest> applicationManifestFrom(File manifestFile) {
        log.debug("manifest: " + manifestFile.getAbsolutePath());
        YamlMapFactoryBean yamlMapFactoryBean = new YamlMapFactoryBean();
        yamlMapFactoryBean.setResources(new FileSystemResource(manifestFile));
        yamlMapFactoryBean.afterPropertiesSet();
        Map<String, Object> manifestYmlFile = yamlMapFactoryBean.getObject();
        ApplicationManifest.Builder builder = ApplicationManifest.builder();
        Map lhm = Map.class.cast(List.class.cast(manifestYmlFile.get("applications"))
                .iterator().next());
        optionalIfExists(lhm, "name", String.class).ifPresent(builder::name);
        optionalIfExists(lhm, "buildpack", String.class)
                .ifPresent(builder::buildpack);
        optionalIfExists(lhm, "memory", String.class).ifPresent(mem -> {
            builder.memory(1024);
        });
        optionalIfExists(lhm, "disk", Integer.class).ifPresent(builder::disk);
        optionalIfExists(lhm, "domains", String.class).ifPresent(builder::domain);
        optionalIfExists(lhm, "instances", Integer.class).ifPresent(
                builder::instances);
        optionalIfExists(lhm, "host", String.class).ifPresent(host -> {
            String rw = "${random-word}";
            if (host.contains(rw)) {
                builder.host(host.replace(rw, UUID.randomUUID().toString()));
            } else {
                builder.host(host);
            }
        });
        optionalIfExists(lhm, "services", Object.class).ifPresent(svcs -> {
            if (svcs instanceof String) {
                builder.host(String.class.cast(svcs));
            } else if (svcs instanceof Iterable) {
                builder.addAllServices(Iterable.class.cast(svcs));
            }
        });
        optionalIfExists(lhm, ("env"), Map.class).ifPresent(
                builder::putAllEnvironmentVariables);
        Map<File, ApplicationManifest> deployManifest = new HashMap<>();
        optionalIfExists(lhm, "path", String.class).map(
                p -> new File(manifestFile.getParentFile(), p)).ifPresent(appPath -> {
            deployManifest.put(appPath, builder.build());
        });
        return deployManifest;
    }

    public PushApplicationRequest fromApplicationManifest(File path, ApplicationManifest applicationManifest) {
        PushApplicationRequest.Builder builder = PushApplicationRequest.builder();
        builder.application(path.toPath());
        if (applicationManifest.getHosts() != null
                && applicationManifest.getHosts().size() > 0) {
            builder.host(applicationManifest.getHosts().iterator().next());
        }
        if (StringUtils.hasText(applicationManifest.getBuildpack())) {
            builder.buildpack(applicationManifest.getBuildpack());
        }
        if (applicationManifest.getMemory() != null) {
            builder.memory(applicationManifest.getMemory());
        }
        if (applicationManifest.getDisk() != null) {
            builder.diskQuota(applicationManifest.getDisk());
        }
        if (applicationManifest.getInstances() != null) {
            builder.instances(applicationManifest.getInstances());
        }
        if (StringUtils.hasText(applicationManifest.getName())) {
            builder.name(applicationManifest.getName());
        }
        if (applicationManifest.getDomains() != null
                && applicationManifest.getDomains().size() > 0) {
            builder.domain(applicationManifest.getDomains().iterator().next());
        }
        if (applicationManifest.getEnvironmentVariables() != null
                && applicationManifest.getEnvironmentVariables().size() > 0) {
            builder.noStart(true);
        }
        if (applicationManifest.getServices() != null
                && applicationManifest.getServices().size() > 0) {
            builder.noStart(true);
        }
        return builder.build();
    }

    public void createService(String svcName, String planName, String instanceName) {
        log.debug("creating service " + svcName + " with plan " + planName
                + " and instance name " + instanceName);
        if (!this.serviceExists(instanceName)) {
            this.cf
                    .services()
                    .createInstance(
                            CreateServiceInstanceRequest.builder().planName(planName)
                                    .serviceInstanceName(instanceName).serviceName(svcName).build()).block();
        }
    }

    public String urlForApplication(String appName) {
        return this.urlForApplication(appName, false);
    }

    public String urlForApplication(String appName, boolean https) {
        return "http"
                + (https ? "s" : "")
                + "://"
                + this.cf.applications()
                .get(GetApplicationRequest.builder().name(appName).build())
                .map(ad -> (ad.getUrls().stream()).findFirst().get()).block();
    }

    public boolean destroyApplicationIfExists(String appName) {
        if (this.applicationExists(appName)) {
            this.cf.applications()
                    .delete(DeleteApplicationRequest.builder().name(appName).build()).block();
            log.debug("destroyed application " + appName);
        }
        return !this.applicationExists(appName);
    }

    public boolean applicationExists(String appName) {
        return this.cf.applications().list()
                .filter(si -> si.getName().equals(appName)).singleOrEmpty().hasElement()
                .block();
    }

    public boolean destroyServiceIfExists(String instance) {
        if (this.serviceExists(instance)) {
            this.cf
                    .services()
                    .deleteInstance(
                            DeleteServiceInstanceRequest.builder().name(instance).build()).block();
            log.debug("destroyed service " + instance);
            return !this.serviceExists(instance);
        }
        return true;
    }
}
