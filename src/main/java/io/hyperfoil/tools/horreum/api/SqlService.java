package io.hyperfoil.tools.horreum.api;

import io.hyperfoil.tools.yaup.StringUtil;
import io.hyperfoil.tools.yaup.json.Json;
import io.quarkus.security.identity.SecurityIdentity;

import javax.annotation.PostConstruct;
import javax.annotation.security.PermitAll;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceException;
import javax.persistence.Query;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.*;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.hibernate.JDBCException;
import org.hibernate.transform.ResultTransformer;
import org.jboss.logging.Logger;

@Path("/api/sql")
@Consumes({MediaType.APPLICATION_JSON})
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class SqlService {
   private static final Logger log = Logger.getLogger(SqlService.class);

   private static final String SET_ROLES = "SELECT set_config('horreum.userroles', ?, false)";
   private static final String SET_TOKEN = "SELECT set_config('horreum.token', ?, false)";
   private static final CloseMe NOOP = () -> {};

   @Inject
   EntityManager em;

   @Inject
   SecurityIdentity identity;

   @ConfigProperty(name = "horreum.db.secret")
   String dbSecret;
   byte[] dbSecretBytes;

   @ConfigProperty(name = "horreum.debug")
   Optional<Boolean> debug;

   private final Map<String, String> signedRoleCache = new ConcurrentHashMap<>();

   @SuppressWarnings("deprecation")
   public static void setResultTransformer(Query query, ResultTransformer transformer) {
      query.unwrap(org.hibernate.query.Query.class).setResultTransformer(transformer);
   }

   @GET
   @PermitAll
   @Path("testjsonpath")
   public Response testJsonPath(@QueryParam("query") String jsonpath) {
      if (jsonpath == null) {
         return Response.status(Response.Status.BAD_REQUEST).entity("No query").build();
      }
      Query query = em.createNativeQuery("SELECT jsonb_path_query_first('{}', ('$' || ?)::::jsonpath)::::text");
      query.setParameter(1, jsonpath);
      Json result = new Json(false);
      try {
         query.getSingleResult();
         result.add("valid", true);
      } catch (PersistenceException pe) {
         result.add("valid", false);
         if (pe.getCause() instanceof JDBCException) {
            JDBCException je = (JDBCException) pe.getCause();
            result.add("errorCode", je.getErrorCode());
            result.add("sqlState", je.getSQLState());
            result.add("reason", je.getSQLException().getMessage());
            result.add("sql", je.getSQL());
         } else {
            result.add("reason", pe.getMessage());
         }
      }
      return Response.ok(result).build();
   }

   @PostConstruct
   void init() {
      log.info("Initializing SqlService");
      dbSecretBytes = dbSecret.getBytes(StandardCharsets.UTF_8);
   }

   public static Json fromResultSet(ResultSet resultSet) throws SQLException {
      Json rtrn = new Json(true);
      Map<String, Integer> names = new HashMap<>();
      ResultSetMetaData rsmd = resultSet.getMetaData();
      int columnCount = rsmd.getColumnCount();
      for (int i = 1; i <= columnCount; i++) {
         String name = rsmd.getColumnName(i);
         names.put(name, rsmd.getColumnType(i));
      }

      while (resultSet.next()) {
         Json entry = new Json();
         for (String name : names.keySet()) {
            Object value = getValue(resultSet, name, names.get(name));
            entry.set(name, value );
         }
         rtrn.add(entry);
      }
      return rtrn;
   }

   public static Object getValue(ResultSet resultSet, String column, int type) throws SQLException {
      switch (type) {

         case Types.DATE:
         case Types.TIME:
         case Types.TIMESTAMP:
         case Types.TIMESTAMP_WITH_TIMEZONE:
            return resultSet.getTimestamp(column).getTime();
         case Types.JAVA_OBJECT:
            Object obj = resultSet.getObject(column);
            if (obj == null) {
               return "";
            } else {
               return Json.fromString(obj.toString());
            }

         case Types.TINYINT:
         case Types.SMALLINT:
         case Types.INTEGER:
         case Types.BIGINT:
            return resultSet.getLong(column);
         case Types.OTHER:
            String str = StringUtil.removeQuotes(resultSet.getString(column));
            if (Json.isJsonLike(str)) {
               return Json.fromString(str);
            } else {
               return str;
            }
         case Types.BIT:
         case Types.BOOLEAN:
            return resultSet.getBoolean(column);
         default:
            return StringUtil.removeQuotes(resultSet.getString(column));
      }
   }

   private String getSignedRoles(Iterable<String> roles) throws NoSuchAlgorithmException {
      StringBuilder sb = new StringBuilder();
      for (String role : roles) {
         String signedRole = signedRoleCache.get(role);
         if (signedRole == null) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(role.getBytes(StandardCharsets.UTF_8));
            String salt = Long.toHexString(ThreadLocalRandom.current().nextLong());
            digest.update(salt.getBytes(StandardCharsets.UTF_8));
            digest.update(dbSecretBytes);
            String signature = Base64.getEncoder().encodeToString(digest.digest());
            signedRole = role + ':' + salt + ':' + signature;
            // We don't care about race conditions, all signed roles are equally correct
            signedRoleCache.putIfAbsent(role, signedRole);
         }
         if (sb.length() != 0) {
            sb.append(',');
         }
         sb.append(signedRole);
      }
      return sb.toString();
   }

   @PermitAll
   @Path("roles")
   @GET
   @Produces("text/plain")
   public String roles() {
      if (!debug.orElse(false)) {
         throw new WebApplicationException(404);
      }
      if (identity.isAnonymous()) {
         return "<anonymous>";
      }
      List<String> roles = new ArrayList<>(identity.getRoles());
      roles.add(identity.getPrincipal().getName());
      try {
         return SET_ROLES.replace("?", '\'' + getSignedRoles(roles) + '\'');
      } catch (NoSuchAlgorithmException e) {
         return "<error>";
      }
   }

   CloseMe withRoles(EntityManager em, Iterable<String> roles) {
      String signedRoles;
      try {
         signedRoles = getSignedRoles(roles);
      } catch (NoSuchAlgorithmException e) {
         throw new IllegalStateException(e);
      }
      return withRoles(em, signedRoles);
   }

   CloseMe withRoles(EntityManager em, SecurityIdentity identity) {
      if (identity.isAnonymous()) {
         return NOOP;
      }
      String signedRoles;
      try {
         List<String> roles = new ArrayList<>(identity.getRoles());
         roles.add(identity.getPrincipal().getName());
         signedRoles = getSignedRoles(roles);
      } catch (NoSuchAlgorithmException e) {
         throw new IllegalStateException(e);
      }
      return withRoles(em, signedRoles);
   }

   private CloseMe withRoles(EntityManager em, String signedRoles) {
      Query setRoles = em.createNativeQuery(SET_ROLES);
      setRoles.setParameter(1, signedRoles);
      setRoles.getSingleResult(); // ignored
      return () -> {
         Query unsetRoles = em.createNativeQuery(SET_ROLES);
         unsetRoles.setParameter(1, "");
         unsetRoles.getSingleResult(); // ignored
      };
   }

   CloseMe withToken(EntityManager em, String token) {
      if (token == null || token.isEmpty()) {
         return NOOP;
      } else {
         Query setToken = em.createNativeQuery(SET_TOKEN);
         setToken.setParameter(1, token);
         setToken.getSingleResult();
         return () -> {
            Query unsetToken = em.createNativeQuery(SET_TOKEN);
            unsetToken.setParameter(1, "");
            unsetToken.getSingleResult();
         };
      }
   }

   public static ResultSet execute(PreparedStatement statement) throws SQLException {
      long startTime = System.nanoTime();
      try {
         return statement.executeQuery();
      } finally {
         long endTime = System.nanoTime();
         log.debugf("SQL query execution took %d ms, query: %s", TimeUnit.NANOSECONDS.toMillis(endTime - startTime), statement);
      }
   }
}
