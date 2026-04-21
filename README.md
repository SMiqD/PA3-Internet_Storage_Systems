Open a terminal in the folder containing server.java and client.java:

If javac is on PATH:

javac server.java client.java
If not, use the included JDK:

.javac.exe server.java client.java
Run Server (Machine 1)
Choose a port (example 5000):

java server 5000
or .java.exe server 5000
Leave server running.

Run Client (Machine 2 / Machine 3)
Connect to the server:

Local test:

java client 127.0.0.1 5000
On other laptops (replace with server LAN IP):

java client <server_ip> 5000
Client should print: Server: Hello!
