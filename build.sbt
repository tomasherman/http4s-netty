name := "http4s-netty"

libraryDependencies += "co.fs2"             %% "fs2-reactive-streams"         % "2.0.1"
libraryDependencies += "com.typesafe.netty" % "netty-reactive-streams-http"   % "2.0.4"
libraryDependencies += "org.http4s"         %% "http4s-server"                % "0.21.0-M5"
libraryDependencies += "org.http4s"         %% "http4s-dsl"                   % "0.21.0-M5"
libraryDependencies += "ch.qos.logback"     % "logback-classic"               % "1.2.3"
libraryDependencies += "io.netty"           % "netty-transport-native-epoll"  % "4.1.43.Final" classifier "linux-x86_64"
libraryDependencies += "io.netty"           % "netty-transport-native-kqueue" % "4.1.43.Final" classifier "osx-x86_64"
