% Generated by roxygen2: do not edit by hand
% Please edit documentation in R/Meta-methods.R
\docType{methods}
\name{metaClient,OpencgaR-method}
\alias{metaClient,OpencgaR-method}
\title{MetaClient methods}
\usage{
\S4method{metaClient}{OpencgaR}(OpencgaR, action, params = NULL, ...)
}
\arguments{
\item{OpencgaR}{an object OpencgaR generated using initOpencgaR and/or opencgaLogin 
where the connection and session details are stored}

\item{action}{action to be performed on the variableSet(s)}

\item{params}{list containing additional query or body params}
}
\description{
This function implements the OpenCGA calls for managing Meta
}
\seealso{
\url{https://github.com/opencb/opencga/wiki} and the RESTful API documentation 
\url{http://bioinfo.hpc.cam.ac.uk/opencga/webservices/}
}
